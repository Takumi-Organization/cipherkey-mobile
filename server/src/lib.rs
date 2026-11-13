use axum::{
    body::Bytes,
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, Method, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
    Json, Router,
};
use base64::{engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD}, Engine};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use rand::RngCore;
use rusqlite::{params, Connection, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

pub const MAX_BLOB: usize = 256 * 1024;
pub const MAX_OPS: usize = 200;
const CLOCK_SKEW_MS: i64 = 5 * 60 * 1000;
const ENROLL_TTL_MS: i64 = 10 * 60 * 1000;

#[derive(Clone)]
pub struct Config {
    pub bind: String,
    pub db: String,
    /// Si está definido, crear bóvedas exige la cabecera X-Admin-Token con este valor.
    pub admin_token: Option<String>,
    /// Permite crear bóvedas sin token (solo para pruebas o servidores privados).
    pub open_registration: bool,
    pub trust_proxy: bool,
    pub tombstone_days: i64,
    /// Peticiones por minuto y por IP (autenticadas) y para endpoints sin autenticar / fallos de firma.
    pub rate_per_min: u32,
    pub anon_per_min: u32,
}

impl Config {
    pub fn from_env() -> Self {
        let get = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
        Config {
            bind: get("CK_BIND").unwrap_or_else(|| "127.0.0.1:8787".into()),
            db: get("CK_DB").unwrap_or_else(|| "cipherkey.db".into()),
            admin_token: get("CK_ADMIN_TOKEN"),
            open_registration: get("CK_OPEN_REGISTRATION").as_deref() == Some("1"),
            trust_proxy: get("CK_TRUST_PROXY").as_deref() == Some("1"),
            tombstone_days: get("CK_TOMBSTONE_DAYS").and_then(|v| v.parse().ok()).unwrap_or(90),
            rate_per_min: get("CK_RATE_PER_MIN").and_then(|v| v.parse().ok()).unwrap_or(600),
            anon_per_min: get("CK_ANON_PER_MIN").and_then(|v| v.parse().ok()).unwrap_or(20),
        }
    }
}

pub struct AppState {
    db: Mutex<Connection>,
    cfg: Config,
    limits: Mutex<HashMap<(IpAddr, bool), (i64, u32)>>,
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

impl AppState {
    pub fn open(cfg: Config) -> rusqlite::Result<Self> {
        let conn = Connection::open(&cfg.db)?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA synchronous=FULL;
             CREATE TABLE IF NOT EXISTS vaults(vault_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL);
             CREATE TABLE IF NOT EXISTS devices(
                device_id TEXT PRIMARY KEY, vault_id TEXT NOT NULL REFERENCES vaults(vault_id),
                name TEXT NOT NULL, public_key TEXT NOT NULL, created_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0);
             CREATE TABLE IF NOT EXISTS enrollments(
                token_hash TEXT PRIMARY KEY, vault_id TEXT NOT NULL, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0);
             CREATE TABLE IF NOT EXISTS nonces(device_id TEXT NOT NULL, nonce TEXT NOT NULL, ts INTEGER NOT NULL,
                PRIMARY KEY(device_id, nonce));
             CREATE TABLE IF NOT EXISTS records(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, vault_id TEXT NOT NULL, uuid TEXT NOT NULL, ver INTEGER NOT NULL,
                device_id TEXT NOT NULL, deleted INTEGER NOT NULL, blob BLOB NOT NULL, ts INTEGER NOT NULL,
                UNIQUE(vault_id, uuid));
             CREATE TABLE IF NOT EXISTS ops(vault_id TEXT NOT NULL, op_id TEXT NOT NULL, ver INTEGER NOT NULL, seq INTEGER NOT NULL,
                PRIMARY KEY(vault_id, op_id));
             CREATE INDEX IF NOT EXISTS idx_records_vault_seq ON records(vault_id, seq);",
        )?;
        Ok(AppState { db: Mutex::new(conn), cfg, limits: Mutex::new(HashMap::new()) })
    }

    pub fn backup(&self, dest: &str) -> rusqlite::Result<()> {
        let db = self.db.lock().unwrap();
        db.execute("VACUUM INTO ?1", params![dest])?;
        Ok(())
    }

    fn purge(&self) {
        let db = self.db.lock().unwrap();
        let now = now_ms();
        let _ = db.execute("DELETE FROM nonces WHERE ts < ?1", params![now - 2 * CLOCK_SKEW_MS]);
        let _ = db.execute("DELETE FROM enrollments WHERE expires_at < ?1 OR used = 1", params![now - ENROLL_TTL_MS]);
        let cutoff = now - self.cfg.tombstone_days * 86_400_000;
        let _ = db.execute("DELETE FROM records WHERE deleted = 1 AND ts < ?1", params![cutoff]);
        let _ = db.execute("DELETE FROM ops WHERE seq NOT IN (SELECT seq FROM records)", []);
    }

    /// Ventana fija de 1 minuto por IP. `anon` usa el límite estricto (sin firma, o fallos de firma).
    fn rate_ok(&self, ip: IpAddr, anon: bool) -> bool {
        let limit = if anon { self.cfg.anon_per_min } else { self.cfg.rate_per_min };
        let mut m = self.limits.lock().unwrap();
        let now = now_ms();
        if m.len() > 10_000 {
            m.retain(|_, v| now - v.0 < 60_000);
        }
        let e = m.entry((ip, anon)).or_insert((now, 0));
        if now - e.0 >= 60_000 {
            *e = (now, 0);
        }
        e.1 += 1;
        e.1 <= limit
    }
}

pub fn spawn_maintenance(state: Arc<AppState>) {
    tokio::spawn(async move {
        loop {
            state.purge();
            tokio::time::sleep(std::time::Duration::from_secs(3600)).await;
        }
    });
}

// ------------------------------------------------------------------------- errores

pub struct ApiError(StatusCode, &'static str);
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}
fn err(code: u16, msg: &'static str) -> ApiError {
    ApiError(StatusCode::from_u16(code).unwrap(), msg)
}
impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        eprintln!("error de base de datos: {e}");
        err(500, "error interno")
    }
}

type Res = Result<Json<Value>, ApiError>;
type Shared = State<Arc<AppState>>;

fn client_ip(state: &AppState, addr: SocketAddr, headers: &HeaderMap) -> IpAddr {
    if state.cfg.trust_proxy {
        if let Some(v) = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok()) {
            if let Some(first) = v.split(',').next().and_then(|s| s.trim().parse().ok()) {
                return first;
            }
        }
    }
    addr.ip()
}

// ------------------------------------------------------------------------- autenticación

struct Authed {
    device_id: String,
    vault_id: String,
}

/// Verifica `Authorization: CK1 device=..,ts=..,nonce=..,sig=..` sobre método, ruta+query, ts, nonce y SHA-256 del cuerpo.
fn authenticate(state: &AppState, ip: IpAddr, method: &Method, uri: &Uri, headers: &HeaderMap, body: &[u8]) -> Result<Authed, ApiError> {
    if !state.rate_ok(ip, false) {
        return Err(err(429, "demasiadas peticiones"));
    }
    let fail = |code: u16, msg: &'static str| -> ApiError {
        // Los fallos de autenticación consumen el límite estricto: frena la fuerza bruta y los sondeos.
        if !state.rate_ok(ip, true) {
            return err(429, "demasiados fallos de autenticación");
        }
        err(code, msg)
    };
    let h = headers.get("authorization").and_then(|v| v.to_str().ok()).ok_or_else(|| fail(401, "falta autorización"))?;
    let rest = h.strip_prefix("CK1 ").ok_or_else(|| fail(401, "esquema no soportado"))?;
    let mut p: HashMap<&str, &str> = HashMap::new();
    for kv in rest.split(',') {
        if let Some((k, v)) = kv.trim().split_once('=') {
            p.insert(k, v);
        }
    }
    let (device, ts, nonce, sig) = match (p.get("device"), p.get("ts"), p.get("nonce"), p.get("sig")) {
        (Some(d), Some(t), Some(n), Some(s)) => (*d, *t, *n, *s),
        _ => return Err(fail(401, "cabecera incompleta")),
    };
    let ts: i64 = ts.parse().map_err(|_| fail(401, "ts inválido"))?;
    if (now_ms() - ts).abs() > CLOCK_SKEW_MS {
        return Err(fail(401, "marca de tiempo fuera de rango"));
    }
    if nonce.len() > 64 {
        return Err(fail(401, "nonce inválido"));
    }
    let db = state.db.lock().unwrap();
    let row: Option<(String, String, i64)> = db
        .query_row("SELECT vault_id, public_key, revoked FROM devices WHERE device_id = ?1", params![device], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?))
        })
        .optional()?;
    let (vault_id, pubkey, revoked) = row.ok_or_else(|| fail(401, "dispositivo desconocido"))?;
    if revoked != 0 {
        return Err(fail(403, "dispositivo revocado"));
    }
    let pk = STANDARD.decode(pubkey).ok().and_then(|b| <[u8; 32]>::try_from(b).ok()).and_then(|b| VerifyingKey::from_bytes(&b).ok());
    let sig_bytes = URL_SAFE_NO_PAD.decode(sig).ok().and_then(|b| <[u8; 64]>::try_from(b).ok());
    let (pk, sig_bytes) = match (pk, sig_bytes) {
        (Some(a), Some(b)) => (a, b),
        _ => return Err(fail(401, "firma inválida")),
    };
    let path = uri.path_and_query().map(|x| x.as_str()).unwrap_or("/");
    let msg = format!("{}\n{}\n{}\n{}\n{}", method.as_str(), path, ts, nonce, hex::encode(Sha256::digest(body)));
    if pk.verify(msg.as_bytes(), &Signature::from_bytes(&sig_bytes)).is_err() {
        return Err(fail(401, "firma inválida"));
    }
    // Anti-repetición: cada nonce solo vale una vez por dispositivo (dentro de la ventana de ±5 min).
    let inserted = db.execute("INSERT OR IGNORE INTO nonces(device_id, nonce, ts) VALUES (?1, ?2, ?3)", params![device, nonce, now_ms()])?;
    if inserted == 0 {
        return Err(fail(401, "petición repetida"));
    }
    db.execute("UPDATE devices SET last_seen = ?1 WHERE device_id = ?2", params![now_ms(), device])?;
    Ok(Authed { device_id: device.to_string(), vault_id })
}

fn sha_hex(s: &str) -> String {
    hex::encode(Sha256::digest(s.as_bytes()))
}

fn random_token() -> String {
    let mut b = [0u8; 24];
    rand::rngs::OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

fn new_device_id() -> String {
    let mut b = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut b);
    hex::encode(b)
}

fn valid_pubkey(s: &str) -> bool {
    STANDARD.decode(s).ok().and_then(|b| <[u8; 32]>::try_from(b).ok()).and_then(|b| VerifyingKey::from_bytes(&b).ok()).is_some()
}

fn valid_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == ':')
}

// ------------------------------------------------------------------------- handlers

#[derive(Deserialize)]
struct CreateVault {
    #[serde(rename = "vaultId")]
    vault_id: String,
    #[serde(rename = "devicePublicKey")]
    device_public_key: String,
    #[serde(rename = "deviceName")]
    device_name: String,
}

async fn create_vault(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Json(b): Json<CreateVault>) -> Res {
    let ip = client_ip(&st, addr, &headers);
    if !st.rate_ok(ip, true) {
        return Err(err(429, "demasiadas peticiones"));
    }
    match &st.cfg.admin_token {
        Some(t) => {
            let given = headers.get("x-admin-token").and_then(|v| v.to_str().ok()).unwrap_or("");
            // comparación sobre hashes: evita filtrar la longitud y no corta al primer byte distinto
            if sha_hex(given) != sha_hex(t) {
                return Err(err(403, "token de administrador incorrecto"));
            }
        }
        None if !st.cfg.open_registration => return Err(err(403, "el registro de bóvedas está desactivado en este servidor")),
        None => {}
    }
    if !valid_id(&b.vault_id) || !valid_pubkey(&b.device_public_key) || b.device_name.len() > 80 {
        return Err(err(400, "datos inválidos"));
    }
    let db = st.db.lock().unwrap();
    let exists: Option<i64> = db.query_row("SELECT 1 FROM vaults WHERE vault_id = ?1", params![b.vault_id], |r| r.get(0)).optional()?;
    if exists.is_some() {
        return Err(err(409, "la bóveda ya existe"));
    }
    let device_id = new_device_id();
    let now = now_ms();
    db.execute("INSERT INTO vaults(vault_id, created_at) VALUES (?1, ?2)", params![b.vault_id, now])?;
    db.execute(
        "INSERT INTO devices(device_id, vault_id, name, public_key, created_at, last_seen) VALUES (?1,?2,?3,?4,?5,?5)",
        params![device_id, b.vault_id, b.device_name, b.device_public_key, now],
    )?;
    Ok(Json(json!({ "deviceId": device_id })))
}

async fn new_enrollment(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, method: Method, uri: Uri, headers: HeaderMap, body: Bytes) -> Res {
    let ip = client_ip(&st, addr, &headers);
    let a = authenticate(&st, ip, &method, &uri, &headers, &body)?;
    let token = random_token();
    let expires = now_ms() + ENROLL_TTL_MS;
    let db = st.db.lock().unwrap();
    db.execute("INSERT INTO enrollments(token_hash, vault_id, expires_at) VALUES (?1,?2,?3)", params![sha_hex(&token), a.vault_id, expires])?;
    Ok(Json(json!({ "token": token, "expiresAt": expires })))
}

#[derive(Deserialize)]
struct Enroll {
    #[serde(rename = "vaultId")]
    vault_id: String,
    token: String,
    #[serde(rename = "devicePublicKey")]
    device_public_key: String,
    #[serde(rename = "deviceName")]
    device_name: String,
}

async fn enroll(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Json(b): Json<Enroll>) -> Res {
    let ip = client_ip(&st, addr, &headers);
    if !st.rate_ok(ip, true) {
        return Err(err(429, "demasiadas peticiones"));
    }
    if !valid_id(&b.vault_id) || !valid_pubkey(&b.device_public_key) || b.device_name.len() > 80 {
        return Err(err(400, "datos inválidos"));
    }
    let mut db = st.db.lock().unwrap();
    let tx = db.transaction()?;
    let now = now_ms();
    // El token solo se consume una vez, y solo si pertenece a esa bóveda y no ha caducado.
    let updated = tx.execute(
        "UPDATE enrollments SET used = 1 WHERE token_hash = ?1 AND vault_id = ?2 AND used = 0 AND expires_at > ?3",
        params![sha_hex(&b.token), b.vault_id, now],
    )?;
    if updated == 0 {
        return Err(err(403, "código de emparejamiento inválido o caducado"));
    }
    let device_id = new_device_id();
    tx.execute(
        "INSERT INTO devices(device_id, vault_id, name, public_key, created_at, last_seen) VALUES (?1,?2,?3,?4,?5,?5)",
        params![device_id, b.vault_id, b.device_name, b.device_public_key, now],
    )?;
    tx.commit()?;
    Ok(Json(json!({ "deviceId": device_id })))
}

async fn list_devices(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, method: Method, uri: Uri, headers: HeaderMap, body: Bytes) -> Res {
    let ip = client_ip(&st, addr, &headers);
    let a = authenticate(&st, ip, &method, &uri, &headers, &body)?;
    let db = st.db.lock().unwrap();
    let mut stmt = db.prepare("SELECT device_id, name, created_at, last_seen, revoked FROM devices WHERE vault_id = ?1 ORDER BY created_at")?;
    let rows = stmt.query_map(params![a.vault_id], |r| {
        Ok(json!({ "deviceId": r.get::<_, String>(0)?, "name": r.get::<_, String>(1)?, "createdAt": r.get::<_, i64>(2)?,
                   "lastSeen": r.get::<_, i64>(3)?, "revoked": r.get::<_, i64>(4)? != 0 }))
    })?;
    Ok(Json(Value::Array(rows.collect::<Result<Vec<_>, _>>()?)))
}

async fn revoke_device(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, method: Method, uri: Uri, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> Res {
    let ip = client_ip(&st, addr, &headers);
    let a = authenticate(&st, ip, &method, &uri, &headers, &body)?;
    let db = st.db.lock().unwrap();
    let n = db.execute("UPDATE devices SET revoked = 1 WHERE device_id = ?1 AND vault_id = ?2", params![id, a.vault_id])?;
    if n == 0 {
        return Err(err(404, "dispositivo no encontrado"));
    }
    Ok(Json(json!({ "revoked": id })))
}

#[derive(Deserialize)]
struct ChangesQuery {
    since: Option<i64>,
    limit: Option<i64>,
}

async fn get_changes(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, method: Method, uri: Uri, headers: HeaderMap, Query(q): Query<ChangesQuery>, body: Bytes) -> Res {
    let ip = client_ip(&st, addr, &headers);
    let a = authenticate(&st, ip, &method, &uri, &headers, &body)?;
    let since = q.since.unwrap_or(0);
    let limit = q.limit.unwrap_or(200).clamp(1, 500);
    let db = st.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT uuid, ver, seq, device_id, deleted, blob FROM records WHERE vault_id = ?1 AND seq > ?2 ORDER BY seq LIMIT ?3",
    )?;
    let rows: Vec<Value> = stmt
        .query_map(params![a.vault_id, since, limit + 1], |r| Ok(change_json(r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get::<_, i64>(4)? != 0, r.get(5)?)))?
        .collect::<Result<_, _>>()?;
    let more = rows.len() as i64 > limit;
    let rows: Vec<Value> = rows.into_iter().take(limit as usize).collect();
    let latest: i64 = db.query_row("SELECT COALESCE(MAX(seq), 0) FROM records WHERE vault_id = ?1", params![a.vault_id], |r| r.get(0))?;
    Ok(Json(json!({ "changes": rows, "latestSeq": latest, "more": more })))
}

fn change_json(uuid: String, ver: i64, seq: i64, device: String, deleted: bool, blob: Vec<u8>) -> Value {
    json!({ "uuid": uuid, "ver": ver, "seq": seq, "deviceId": device, "deleted": deleted, "blob": STANDARD.encode(blob) })
}

#[derive(Deserialize)]
struct PushBody {
    ops: Vec<PushOp>,
}
#[derive(Deserialize)]
struct PushOp {
    #[serde(rename = "opId")]
    op_id: String,
    uuid: String,
    #[serde(rename = "baseVer")]
    base_ver: i64,
    deleted: bool,
    blob: String,
}

async fn post_changes(State(st): Shared, ConnectInfo(addr): ConnectInfo<SocketAddr>, method: Method, uri: Uri, headers: HeaderMap, body: Bytes) -> Res {
    let ip = client_ip(&st, addr, &headers);
    let a = authenticate(&st, ip, &method, &uri, &headers, &body)?;
    let req: PushBody = serde_json::from_slice(&body).map_err(|_| err(400, "cuerpo inválido"))?;
    if req.ops.len() > MAX_OPS {
        return Err(err(413, "demasiadas operaciones"));
    }
    let mut db = st.db.lock().unwrap();
    let tx = db.transaction()?;
    let mut results = Vec::new();
    for op in req.ops {
        if !valid_id(&op.op_id) || !valid_id(&op.uuid) || op.base_ver < 0 {
            results.push(json!({ "opId": op.op_id, "status": "rejected" }));
            continue;
        }
        let blob = match STANDARD.decode(&op.blob) {
            Ok(b) if b.len() <= MAX_BLOB && b.len() >= 28 => b,
            _ => {
                results.push(json!({ "opId": op.op_id, "status": "rejected" }));
                continue;
            }
        };
        // Idempotencia: una operación ya aplicada devuelve su resultado original, sin duplicar nada.
        let prev: Option<(i64, i64)> = tx
            .query_row("SELECT ver, seq FROM ops WHERE vault_id = ?1 AND op_id = ?2", params![a.vault_id, op.op_id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?;
        if let Some((ver, seq)) = prev {
            results.push(json!({ "opId": op.op_id, "status": "ok", "ver": ver, "seq": seq }));
            continue;
        }
        let head: Option<(i64, i64, String, bool, Vec<u8>)> = tx
            .query_row("SELECT ver, seq, device_id, deleted, blob FROM records WHERE vault_id = ?1 AND uuid = ?2", params![a.vault_id, op.uuid], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? != 0, r.get(4)?))
            })
            .optional()?;
        let head_ver = head.as_ref().map(|h| h.0).unwrap_or(0);
        if head_ver != op.base_ver {
            let current = head.map(|(ver, seq, dev, del, blob)| change_json(op.uuid.clone(), ver, seq, dev, del, blob));
            results.push(json!({ "opId": op.op_id, "status": "conflict", "ver": head_ver, "current": current }));
            continue;
        }
        let new_ver = head_ver + 1;
        tx.execute("DELETE FROM records WHERE vault_id = ?1 AND uuid = ?2", params![a.vault_id, op.uuid])?;
        tx.execute(
            "INSERT INTO records(vault_id, uuid, ver, device_id, deleted, blob, ts) VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![a.vault_id, op.uuid, new_ver, a.device_id, op.deleted as i64, blob, now_ms()],
        )?;
        let seq = tx.last_insert_rowid();
        tx.execute("INSERT INTO ops(vault_id, op_id, ver, seq) VALUES (?1,?2,?3,?4)", params![a.vault_id, op.op_id, new_ver, seq])?;
        results.push(json!({ "opId": op.op_id, "status": "ok", "ver": new_ver, "seq": seq }));
    }
    tx.commit()?;
    Ok(Json(json!({ "results": results })))
}

async fn health() -> Json<Value> {
    Json(json!({ "status": "ok", "protocol": 1 }))
}

pub fn build_app(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/vaults", post(create_vault))
        .route("/v1/enroll", post(enroll))
        .route("/v1/enrollments", post(new_enrollment))
        .route("/v1/devices", get(list_devices))
        .route("/v1/devices/{id}", delete(revoke_device))
        .route("/v1/changes", get(get_changes).post(post_changes))
        .layer(DefaultBodyLimit::max(4 * 1024 * 1024))
        .with_state(state)
}
