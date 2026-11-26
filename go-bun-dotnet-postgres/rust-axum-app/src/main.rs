use std::borrow::Cow;
use std::env;
use std::fs;
use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::get;
use axum::{Json, Router};
use chrono::{DateTime, Utc};
use deadpool_postgres::{Config as PgConfig, ManagerConfig, Pool, PoolConfig, RecyclingMethod, Runtime};
use serde::{Deserialize, Serialize};
use tokio::net::TcpListener;
use tokio_postgres::NoTls;

#[derive(Clone)]
struct AppState {
    pool: Pool,
    insert_sql: Arc<str>,
    app_name: Arc<str>,
}

#[derive(Deserialize)]
struct CreateUserRequest {
    name: String,
    address: String,
    phone: String,
}

#[derive(Serialize)]
struct User {
    id: i32,
    name: Cow<'static, str>,
    address: Cow<'static, str>,
    phone: Cow<'static, str>,
    image: Cow<'static, str>,
    #[serde(rename = "createdAt", skip_serializing_if = "Option::is_none")]
    created_at: Option<DateTime<Utc>>,
    #[serde(rename = "updatedAt", skip_serializing_if = "Option::is_none")]
    updated_at: Option<DateTime<Utc>>,
}

#[derive(Serialize)]
struct ErrorResponse {
    message: String,
}

struct AppConfig {
    app_port: u16,
    db_user: String,
    db_password: String,
    db_host: String,
    db_database: String,
    db_max_connections: usize,
    db_table: String,
}

impl AppConfig {
    fn load(path: &str) -> Self {
        let mut cfg = Self {
            app_port: 8080,
            db_user: "rust_axum_app".to_string(),
            db_password: "devops123".to_string(),
            db_host: "localhost".to_string(),
            db_database: "store".to_string(),
            db_max_connections: 100,
            db_table: "rust_axum_app".to_string(),
        };

        let Ok(content) = fs::read_to_string(path) else {
            return cfg;
        };

        let mut table_explicitly_set = false;
        for raw_line in content.lines() {
            let line = raw_line.trim();
            if line.is_empty() || line.starts_with('#') || line == "---" || line == "db:" {
                continue;
            }
            let Some((k, v)) = line.split_once(':') else {
                continue;
            };
            let key = k.trim();
            let value = v.trim().trim_matches(|c| c == '"' || c == '\'');

            match key {
                "appPort" => {
                    if let Ok(port) = value.parse::<u16>() {
                        cfg.app_port = port;
                    }
                }
                "user" => {
                    cfg.db_user = value.to_string();
                    if !table_explicitly_set {
                        cfg.db_table = value.to_string();
                    }
                }
                "password" => cfg.db_password = value.to_string(),
                "host" => cfg.db_host = value.to_string(),
                "database" => cfg.db_database = value.to_string(),
                "maxConnections" => {
                    if let Ok(max_conn) = value.parse::<usize>() {
                        cfg.db_max_connections = max_conn;
                    }
                }
                "table" => {
                    cfg.db_table = value.to_string();
                    table_explicitly_set = true;
                }
                _ => {}
            }
        }

        cfg
    }
}

fn main() {
    let mut rt_builder = tokio::runtime::Builder::new_multi_thread();
    rt_builder.enable_all();

    if let Ok(val) = env::var("TOKIO_WORKER_THREADS") {
        if let Ok(threads) = val.parse::<usize>() {
            if threads > 0 {
                rt_builder.worker_threads(threads);
            }
        }
    }

    let rt = rt_builder.build().expect("failed to build tokio runtime");
    rt.block_on(async_main());
}

async fn async_main() {
    let config_path = env::var("CONFIG_PATH").unwrap_or_else(|_| "config.yaml".to_string());
    let cfg = AppConfig::load(&config_path);

    if !cfg
        .db_table
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'_')
    {
        panic!("Invalid table name: {}", cfg.db_table);
    }

    let app_name = env::var("APP_NAME").unwrap_or_else(|_| "rust-axum".to_string());

    let mut pg_cfg = PgConfig::new();
    pg_cfg.host = Some(cfg.db_host);
    pg_cfg.port = Some(5432);
    pg_cfg.user = Some(cfg.db_user);
    pg_cfg.password = Some(cfg.db_password);
    pg_cfg.dbname = Some(cfg.db_database);
    pg_cfg.manager = Some(ManagerConfig {
        recycling_method: RecyclingMethod::Fast,
    });
    pg_cfg.pool = Some(PoolConfig::new(cfg.db_max_connections));

    let pool = pg_cfg
        .create_pool(Some(Runtime::Tokio1), NoTls)
        .expect("failed to create postgres pool");

    let insert_sql = format!(
        "INSERT INTO {} (name, address, phone, image, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id",
        cfg.db_table
    );

    let state = AppState {
        pool,
        insert_sql: Arc::from(insert_sql),
        app_name: Arc::from(app_name.as_str()),
    };

    let app = Router::new()
        .route("/healthz", get(get_health))
        .route("/api/users", get(get_users).post(save_user))
        .with_state(state);

    let bind_addr = format!("0.0.0.0:{}", cfg.app_port);
    let listener = TcpListener::bind(&bind_addr)
        .await
        .expect("failed to bind TCP listener");

    println!("{app_name} is listening on http://{bind_addr} ...");
    axum::serve(listener, app).await.expect("server error");
}

async fn get_health() -> (StatusCode, &'static str) {
    (StatusCode::OK, "OK")
}

async fn get_users() -> Json<[User; 3]> {
    Json([
        User {
            id: 1,
            name: Cow::Borrowed("David D. Patton"),
            address: Cow::Borrowed("1670 Stiles Street"),
            phone: Cow::Borrowed("412-578-3857"),
            image: Cow::Borrowed("user.png"),
            created_at: None,
            updated_at: None,
        },
        User {
            id: 2,
            name: Cow::Borrowed("Gary E. Eaton"),
            address: Cow::Borrowed("828 Collins Avenue"),
            phone: Cow::Borrowed("614-866-1660"),
            image: Cow::Borrowed("user.png"),
            created_at: None,
            updated_at: None,
        },
        User {
            id: 3,
            name: Cow::Borrowed("John J. Fox"),
            address: Cow::Borrowed("1895 Columbia Mine Road"),
            phone: Cow::Borrowed("304-505-3622"),
            image: Cow::Borrowed("user.png"),
            created_at: None,
            updated_at: None,
        },
    ])
}

async fn save_user(
    State(state): State<AppState>,
    Json(payload): Json<CreateUserRequest>,
) -> Result<(StatusCode, Json<User>), (StatusCode, Json<ErrorResponse>)> {
    let now = Utc::now();
    let image = format!("user-{}-{}.png", state.app_name, now.timestamp_millis());

    let client = state.pool.get().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResponse {
                message: e.to_string(),
            }),
        )
    })?;

    let stmt = client
        .prepare_cached(&state.insert_sql)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    message: e.to_string(),
                }),
            )
        })?;

    let row = client
        .query_one(
            &stmt,
            &[
                &payload.name,
                &payload.address,
                &payload.phone,
                &image,
                &now,
                &now,
            ],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    message: e.to_string(),
                }),
            )
        })?;

    let id: i32 = row.get(0);

    let user = User {
        id,
        name: Cow::Owned(payload.name),
        address: Cow::Owned(payload.address),
        phone: Cow::Owned(payload.phone),
        image: Cow::Owned(image),
        created_at: Some(now),
        updated_at: Some(now),
    };

    Ok((StatusCode::CREATED, Json(user)))
}
