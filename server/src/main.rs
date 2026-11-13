//! Servidor de sincronización de CipherKey.
//!
//! Almacena únicamente sobres cifrados por los clientes (AES-256-GCM con una clave que el servidor no conoce) y los
//! metadatos mínimos: uuid, versión, borrado, dispositivo y hora. Autenticación por firma Ed25519 de cada petición.
use cipherkey_server::{build_app, Config, AppState};
use std::{net::SocketAddr, sync::Arc};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let cfg = Config::from_env();
    // Subcomando de copia de seguridad en caliente: `cipherkey-server backup <destino>`
    if args.get(1).map(|s| s.as_str()) == Some("backup") {
        let dest = args.get(2).expect("uso: cipherkey-server backup <ruta>");
        let state = AppState::open(cfg).expect("no se pudo abrir la base de datos");
        state.backup(dest).expect("fallo en la copia");
        println!("copia escrita en {dest}");
        return;
    }
    let bind: SocketAddr = cfg.bind.parse().expect("CK_BIND inválido");
    let state = Arc::new(AppState::open(cfg).expect("no se pudo abrir la base de datos"));
    cipherkey_server::spawn_maintenance(state.clone());
    let app = build_app(state);
    let listener = tokio::net::TcpListener::bind(bind).await.expect("no se pudo abrir el puerto");
    eprintln!("cipherkey-server escuchando en {bind} (poner HTTPS delante con un proxy inverso)");
    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
        .with_graceful_shutdown(async { let _ = tokio::signal::ctrl_c().await; })
        .await
        .unwrap();
}
