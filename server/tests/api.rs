use base64::{engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD}, Engine};
use cipherkey_server::{build_app, AppState, Config};
use ed25519_dalek::{Signer, SigningKey};
use rand::RngCore;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{net::SocketAddr, sync::Arc};

struct Srv {
    base: String,
}

async fn start(admin: Option<&str>, open: bool, anon_per_min: u32) -> Srv {
    let cfg = Config {
        bind: "127.0.0.1:0".into(), db: ":memory:".into(), admin_token: admin.map(String::from), open_registration: open,
        trust_proxy: false, tombstone_days: 90, rate_per_min: 10_000, anon_per_min,
    };
    let state = Arc::new(AppState::open(cfg).unwrap());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = build_app(state);
    tokio::spawn(async move { axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap() });
    Srv { base: format!("http://{addr}") }
}

struct Dev {
    key: SigningKey,
    id: String,
}

impl Dev {
    fn new() -> Dev {
        let mut b = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut b);
        Dev { key: SigningKey::from_bytes(&b), id: String::new() }
    }
    fn public(&self) -> String {
        STANDARD.encode(self.key.verifying_key().to_bytes())
    }
    fn header(&self, method: &str, path: &str, ts: i64, nonce: &str, body: &[u8]) -> String {
        let msg = format!("{}\n{}\n{}\n{}\n{}", method, path, ts, nonce, hex::encode(Sha256::digest(body)));
        let sig = URL_SAFE_NO_PAD.encode(self.key.sign(msg.as_bytes()).to_bytes());
        format!("CK1 device={},ts={},nonce={},sig={}", self.id, ts, nonce, sig)
    }
}

fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

fn nonce() -> String {
    let mut b = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

async fn call(s: &Srv, d: &Dev, method: &str, path: &str, body: Value) -> (u16, Value) {
    call_raw(s, d, method, path, body, now(), &nonce()).await
}

async fn call_raw(s: &Srv, d: &Dev, method: &str, path: &str, body: Value, ts: i64, n: &str) -> (u16, Value) {
    let bytes = if body.is_null() { vec![] } else { serde_json::to_vec(&body).unwrap() };
    let c = reqwest::Client::new();
    let m = reqwest::Method::from_bytes(method.as_bytes()).unwrap();
    let mut rb = c.request(m, format!("{}{}", s.base, path)).header("Authorization", d.header(method, path, ts, n, &bytes));
    if !bytes.is_empty() {
        rb = rb.header("Content-Type", "application/json").body(bytes);
    }
    let r = rb.send().await.unwrap();
    let status = r.status().as_u16();
    (status, r.json().await.unwrap_or(Value::Null))
}

async fn post_json(s: &Srv, path: &str, body: Value, admin: Option<&str>) -> (u16, Value) {
    let mut rb = reqwest::Client::new().post(format!("{}{}", s.base, path)).json(&body);
    if let Some(a) = admin {
        rb = rb.header("X-Admin-Token", a);
    }
    let r = rb.send().await.unwrap();
    (r.status().as_u16(), r.json().await.unwrap_or(Value::Null))
}

async fn new_vault(s: &Srv, vault: &str, admin: Option<&str>) -> Dev {
    let mut d = Dev::new();
    let (st, v) = post_json(s, "/v1/vaults", json!({"vaultId": vault, "devicePublicKey": d.public(), "deviceName": "pc"}), admin).await;
    assert_eq!(st, 200, "{v}");
    d.id = v["deviceId"].as_str().unwrap().to_string();
    d
}

fn blob() -> String {
    STANDARD.encode([7u8; 40])
}

fn op(id: &str, uuid: &str, base: i64) -> Value {
    json!({"opId": id, "uuid": uuid, "baseVer": base, "deleted": false, "blob": blob()})
}

#[tokio::test]
async fn vault_creation_is_closed_by_default_and_needs_admin_token() {
    let s = start(None, false, 100).await;
    let d = Dev::new();
    let (st, _) = post_json(&s, "/v1/vaults", json!({"vaultId":"v1","devicePublicKey":d.public(),"deviceName":"x"}), None).await;
    assert_eq!(st, 403);

    let s2 = start(Some("secreto"), false, 100).await;
    let (st, _) = post_json(&s2, "/v1/vaults", json!({"vaultId":"v1","devicePublicKey":d.public(),"deviceName":"x"}), Some("mal")).await;
    assert_eq!(st, 403);
    new_vault(&s2, "v1", Some("secreto")).await;
    let (st, _) = post_json(&s2, "/v1/vaults", json!({"vaultId":"v1","devicePublicKey":d.public(),"deviceName":"x"}), Some("secreto")).await;
    assert_eq!(st, 409, "no se puede recrear una bóveda");
}

#[tokio::test]
async fn bad_signature_stale_timestamp_and_replay_are_rejected() {
    let s = start(None, true, 1000).await;
    let d = new_vault(&s, "v", None).await;
    let (st, _) = call(&s, &d, "GET", "/v1/devices", Value::Null).await;
    assert_eq!(st, 200);

    // replay: mismo nonce dos veces
    let n = nonce();
    assert_eq!(call_raw(&s, &d, "GET", "/v1/devices", Value::Null, now(), &n).await.0, 200);
    assert_eq!(call_raw(&s, &d, "GET", "/v1/devices", Value::Null, now(), &n).await.0, 401);

    // marca de tiempo vieja
    assert_eq!(call_raw(&s, &d, "GET", "/v1/devices", Value::Null, now() - 10 * 60 * 1000, &nonce()).await.0, 401);

    // firma de otro dispositivo con el id del primero
    let mut other = Dev::new();
    other.id = d.id.clone();
    assert_eq!(call(&s, &other, "GET", "/v1/devices", Value::Null).await.0, 401);

    // el cuerpo firmado debe ser el enviado: firma sobre un cuerpo y se envía otro
    let c = reqwest::Client::new();
    let ts = now();
    let n2 = nonce();
    let h = d.header("POST", "/v1/changes", ts, &n2, b"{\"ops\":[]}");
    let r = c.post(format!("{}/v1/changes", s.base)).header("Authorization", h).header("Content-Type", "application/json")
        .body(serde_json::to_vec(&json!({"ops":[op("a","u",0)]})).unwrap()).send().await.unwrap();
    assert_eq!(r.status().as_u16(), 401);
}

#[tokio::test]
async fn push_pull_idempotency_and_conflicts() {
    let s = start(None, true, 1000).await;
    let d = new_vault(&s, "v", None).await;
    let (st, r) = call(&s, &d, "POST", "/v1/changes", json!({"ops":[op("o1","u1",0)]})).await;
    assert_eq!(st, 200);
    assert_eq!(r["results"][0]["status"], "ok");
    assert_eq!(r["results"][0]["ver"], 1);

    // reintento con el mismo opId: mismo resultado, sin versión nueva
    let (_, r2) = call(&s, &d, "POST", "/v1/changes", json!({"ops":[op("o1","u1",0)]})).await;
    assert_eq!(r2["results"][0]["status"], "ok");
    assert_eq!(r2["results"][0]["ver"], 1);

    // base antigua → conflicto con la versión actual
    let (_, r3) = call(&s, &d, "POST", "/v1/changes", json!({"ops":[op("o2","u1",0)]})).await;
    assert_eq!(r3["results"][0]["status"], "conflict");
    assert_eq!(r3["results"][0]["current"]["ver"], 1);

    // base correcta → versión 2
    let (_, r4) = call(&s, &d, "POST", "/v1/changes", json!({"ops":[op("o3","u1",1)]})).await;
    assert_eq!(r4["results"][0]["ver"], 2);

    let (_, p) = call(&s, &d, "GET", "/v1/changes?since=0&limit=10", Value::Null).await;
    assert_eq!(p["changes"].as_array().unwrap().len(), 1, "solo la cabeza de cada registro");
    assert_eq!(p["changes"][0]["ver"], 2);
    assert_eq!(p["more"], false);

    // blob demasiado corto (no puede ser un sobre AES-GCM) → rechazado
    let bad = json!({"opId":"o4","uuid":"u2","baseVer":0,"deleted":false,"blob": STANDARD.encode([1u8; 5])});
    let (_, r5) = call(&s, &d, "POST", "/v1/changes", json!({"ops":[bad]})).await;
    assert_eq!(r5["results"][0]["status"], "rejected");
}

#[tokio::test]
async fn pagination_reports_more() {
    let s = start(None, true, 1000).await;
    let d = new_vault(&s, "v", None).await;
    let ops: Vec<Value> = (0..5).map(|i| op(&format!("o{i}"), &format!("u{i}"), 0)).collect();
    call(&s, &d, "POST", "/v1/changes", json!({"ops": ops})).await;
    let (_, p) = call(&s, &d, "GET", "/v1/changes?since=0&limit=2", Value::Null).await;
    assert_eq!(p["changes"].as_array().unwrap().len(), 2);
    assert_eq!(p["more"], true);
}

#[tokio::test]
async fn vaults_are_isolated() {
    let s = start(None, true, 1000).await;
    let a = new_vault(&s, "vA", None).await;
    let b = new_vault(&s, "vB", None).await;
    call(&s, &a, "POST", "/v1/changes", json!({"ops":[op("o1","secreto","0".parse::<i64>().unwrap())]})).await;
    let (_, p) = call(&s, &b, "GET", "/v1/changes?since=0", Value::Null).await;
    assert_eq!(p["changes"].as_array().unwrap().len(), 0);
    // B tampoco puede revocar dispositivos de A
    let (st, _) = call(&s, &b, "DELETE", &format!("/v1/devices/{}", a.id), Value::Null).await;
    assert_eq!(st, 404);
    let (_, devs) = call(&s, &a, "GET", "/v1/devices", Value::Null).await;
    assert_eq!(devs.as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn enrollment_is_single_use_scoped_and_revocation_works() {
    let s = start(None, true, 1000).await;
    let pc = new_vault(&s, "v", None).await;
    new_vault(&s, "otra", None).await;
    let (st, e) = call(&s, &pc, "POST", "/v1/enrollments", Value::Null).await;
    assert_eq!(st, 200);
    let token = e["token"].as_str().unwrap().to_string();

    // token de otra bóveda no sirve
    let mut phone = Dev::new();
    let (st, _) = post_json(&s, "/v1/enroll", json!({"vaultId":"otra","token":token,"devicePublicKey":phone.public(),"deviceName":"movil"}), None).await;
    assert_eq!(st, 403);
    // token inventado
    let (st, _) = post_json(&s, "/v1/enroll", json!({"vaultId":"v","token":"x","devicePublicKey":phone.public(),"deviceName":"movil"}), None).await;
    assert_eq!(st, 403);
    // token correcto, una sola vez
    let (st, r) = post_json(&s, "/v1/enroll", json!({"vaultId":"v","token":token,"devicePublicKey":phone.public(),"deviceName":"movil"}), None).await;
    assert_eq!(st, 200);
    phone.id = r["deviceId"].as_str().unwrap().to_string();
    let (st, _) = post_json(&s, "/v1/enroll", json!({"vaultId":"v","token":token,"devicePublicKey":Dev::new().public(),"deviceName":"clon"}), None).await;
    assert_eq!(st, 403, "el token no se puede reutilizar");

    // el móvil funciona y comparte la bóveda
    call(&s, &pc, "POST", "/v1/changes", json!({"ops":[op("o1","u1",0)]})).await;
    let (st, p) = call(&s, &phone, "GET", "/v1/changes?since=0", Value::Null).await;
    assert_eq!(st, 200);
    assert_eq!(p["changes"].as_array().unwrap().len(), 1);

    // revocar el móvil: deja de poder leer y escribir; el PC sigue
    let (st, _) = call(&s, &pc, "DELETE", &format!("/v1/devices/{}", phone.id), Value::Null).await;
    assert_eq!(st, 200);
    assert_eq!(call(&s, &phone, "GET", "/v1/changes?since=0", Value::Null).await.0, 403);
    assert_eq!(call(&s, &phone, "POST", "/v1/changes", json!({"ops":[op("o9","u9",0)]})).await.0, 403);
    assert_eq!(call(&s, &pc, "GET", "/v1/changes?since=0", Value::Null).await.0, 200);
    let (_, devs) = call(&s, &pc, "GET", "/v1/devices", Value::Null).await;
    assert!(devs.as_array().unwrap().iter().any(|d| d["deviceId"] == phone.id.as_str() && d["revoked"] == true));
}

#[tokio::test]
async fn anonymous_endpoints_are_rate_limited() {
    let s = start(None, true, 5).await;
    let d = Dev::new();
    let mut last = 0;
    for i in 0..10 {
        let (st, _) = post_json(&s, "/v1/enroll", json!({"vaultId":"v","token":format!("t{i}"),"devicePublicKey":d.public(),"deviceName":"x"}), None).await;
        last = st;
    }
    assert_eq!(last, 429);
}

#[tokio::test]
async fn failed_signatures_consume_the_strict_limit() {
    let s = start(None, true, 5).await;
    let mut ghost = Dev::new();
    ghost.id = "no-existe".into();
    let mut last = 0;
    for _ in 0..10 {
        last = call(&s, &ghost, "GET", "/v1/devices", Value::Null).await.0;
    }
    assert_eq!(last, 429);
}
