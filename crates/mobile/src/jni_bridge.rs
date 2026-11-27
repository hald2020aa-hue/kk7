//! Android boundary; the app supplies its private directory and Android TLS context.
#![allow(unsafe_code)]
use super::{Manager, Result};
use jni::{EnvUnowned, errors::ThrowRuntimeExAndDefault, jni_mangle, objects::{JClass, JObject, JString}};
use serde_json::{Value, json};
use std::{path::PathBuf, sync::{Mutex, OnceLock}};

static MANAGER: OnceLock<Mutex<Manager>> = OnceLock::new();

fn response(result: Result<Value>) -> String {
    match result {
        Ok(value) => json!({"ok": true, "value": value}).to_string(),
        Err(error) => json!({"ok": false, "error": error}).to_string(),
    }
}

#[jni_mangle("org.misterdownloadmanager.mobile.NativeBridge")]
pub extern "system" fn initialize<'a>(mut raw: EnvUnowned<'a>, _class: JClass<'a>, context: JObject<'a>, directory: JString<'a>) -> JString<'a> {
    raw.with_env(|env| -> jni::errors::Result<_> {
        let dir = directory.try_to_string(env)?;
        let result = if MANAGER.get().is_some() { Ok(json!(true)) } else {
            rustls_platform_verifier::android::init_with_env(env, context)?;
            Manager::open(PathBuf::from(dir)).and_then(|manager| {
                MANAGER.set(Mutex::new(manager)).map_err(|_| "Already initialized".to_owned())?;
                Ok(json!(true))
            })
        };
        JString::new(env, response(result))
    }).resolve::<ThrowRuntimeExAndDefault>()
}

fn dispatch(request: &str) -> Result<Value> {
    let request: Value = serde_json::from_str(request).map_err(super::err)?;
    let mut m = MANAGER.get().ok_or("Native engine not initialized")?.lock().map_err(super::err)?;
    let id = || request["id"].as_u64().ok_or("Missing job ID".to_owned());
    let limit = request["limit"].as_u64();
    match request["op"].as_str() {
        Some("list") => serde_json::to_value(m.list()?).map_err(super::err),
        Some("add") => {
            let url = request["url"].as_str().ok_or("Missing URL")?;
            let count = request["connections"].as_u64().unwrap_or(8).clamp(1, 32) as u32;
            Ok(json!(m.add(url, request["name"].as_str(), count, limit)?))
        }
        Some("start") => { m.start(id()?)?; Ok(json!(true)) }
        Some("pause") => { m.pause(id()?)?; Ok(json!(true)) }
        Some("limit") => { m.set_limit(id()?, limit)?; Ok(json!(true)) }
        _ => Err("Unknown operation".into()),
    }
}

#[jni_mangle("org.misterdownloadmanager.mobile.NativeBridge")]
pub extern "system" fn command<'a>(mut raw: EnvUnowned<'a>, _class: JClass<'a>, request: JString<'a>) -> JString<'a> {
    raw.with_env(|env| -> jni::errors::Result<_> {
        let request = request.try_to_string(env)?;
        JString::new(env, response(dispatch(&request)))
    }).resolve::<ThrowRuntimeExAndDefault>()
}
