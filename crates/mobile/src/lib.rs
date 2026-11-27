//! Mobile controller for the original MDM engine. No dependency on desktop windows.
use mdm_engine::{Destination, Download, Options, Request, download::State};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, io::Write, path::{Path, PathBuf}};
use url::Url;

#[cfg(target_os = "android")]
mod jni_bridge;

type Result<T> = std::result::Result<T, String>;
fn err(e: impl std::fmt::Display) -> String { e.to_string() }

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status { Queued, Running, Stopping, Paused, Complete, Failed }

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Job {
    pub id: u64,
    pub url: String,
    pub name: String,
    pub status: Status,
    pub downloaded: u64,
    pub total: Option<u64>,
    pub connections: u32,
    pub speed_limit: Option<u64>,
    pub error: Option<String>,
    #[serde(default)]
    pub completed_name: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct View {
    #[serde(flatten)]
    pub job: Job,
    pub speed: u64,
    pub active_connections: usize,
    pub file: Option<PathBuf>,
    pub phase: Option<String>,
}

#[derive(Clone, Default, Serialize, Deserialize)]
struct Catalog { next_id: u64, jobs: BTreeMap<u64, Job> }

pub struct Manager {
    root: PathBuf,
    catalog: Catalog,
    runtime: tokio::runtime::Runtime,
    running: BTreeMap<u64, Download>,
}

fn valid_url(s: &str) -> Result<Url> {
    let url = Url::parse(s.trim()).map_err(err)?;
    if !mdm_engine::supported(&url) || url.host_str().is_none() {
        return Err("Unsupported download address".into());
    }
    Ok(url)
}

fn path_for(root: &Path, job: &Job) -> PathBuf { root.join("files").join(job.id.to_string()).join(&job.name) }

fn save(root: &Path, catalog: &Catalog) -> Result<()> {
    // Private app directory; one writer. Rename is atomic on Android's filesystem.
    let temp = root.join("catalog.tmp");
    let target = root.join("catalog.json");
    let bytes = serde_json::to_vec(catalog).map_err(err)?;
    let mut f = fs::File::create(&temp).map_err(err)?;
    f.write_all(&bytes).map_err(err)?;
    f.sync_all().map_err(err)?;
    fs::rename(temp, target).map_err(err)?;
    #[cfg(unix)]
    fs::File::open(root).and_then(|f| f.sync_all()).map_err(err)?;
    Ok(())
}

impl Manager {
    pub fn open(root: PathBuf) -> Result<Self> {
        fs::create_dir_all(root.join("files")).map_err(err)?;
        let mut catalog: Catalog = match fs::read(root.join("catalog.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(err)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Catalog::default(),
            Err(e) => return Err(err(e)),
        };
        for (&id, job) in &mut catalog.jobs {
            if job.id != id || mdm_engine::naming::single_file_name(&job.name).as_deref() != Some(job.name.as_str()) {
                return Err("Invalid stored download metadata".into());
            }
            if matches!(job.status, Status::Running | Status::Stopping) {
                job.status = Status::Paused;
                let path = path_for(&root, job);
                if let Ok(s) = mdm_engine::state::ResumeState::load(&mdm_engine::state::state_path(&path)) {
                    job.downloaded = s.downloaded();
                    job.total = Some(s.size);
                }
                if path.exists() {
                    job.status = Status::Failed;
                    job.error = Some("File exists after an interrupted session; inspect it before retrying".into());
                }
            }
        }
        save(&root, &catalog)?;
        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().worker_threads(2).build().map_err(err)?;
        Ok(Self { root, catalog, runtime, running: BTreeMap::new() })
    }

    fn commit(&mut self, next: Catalog) -> Result<()> {
        save(&self.root, &next)?;
        self.catalog = next;
        Ok(())
    }

    pub fn add(&mut self, address: &str, name: Option<&str>, connections: u32, limit: Option<u64>) -> Result<u64> {
        let url = valid_url(address)?;
        let name = match name.filter(|n| !n.trim().is_empty()) {
            Some(n) if n.contains(['/', '\\', ':']) => return Err("Invalid file name".into()),
            Some(n) => mdm_engine::naming::single_file_name(n).ok_or("Invalid file name")?,
            None => mdm_engine::naming::file_name(None, &url),
        };
        let mut next = self.catalog.clone();
        let id = next.next_id.checked_add(1).ok_or("Too many jobs")?;
        next.next_id = id;
        next.jobs.insert(id, Job { id, url: url.to_string(), name, status: Status::Queued,
            downloaded: 0, total: None, connections: connections.clamp(1, 32),
            speed_limit: limit.filter(|x| *x > 0), error: None, completed_name: None });
        self.commit(next)?;
        Ok(id)
    }

    pub fn start(&mut self, id: u64) -> Result<()> {
        self.refresh()?;
        let mut next = self.catalog.clone();
        let job = next.jobs.get_mut(&id).ok_or("Unknown job")?;
        if self.running.contains_key(&id) || job.status == Status::Complete { return Err("Job cannot be started now".into()); }
        if self.running.len() >= 4 { return Err("Maximum of four simultaneous jobs".into()); }
        let path = path_for(&self.root, job);
        fs::create_dir_all(path.parent().ok_or("Invalid path")?).map_err(err)?;
        if path.exists() { return Err("Destination already exists; it will not be overwritten".into()); }
        let state = mdm_engine::state::state_path(&path);
        let part = mdm_engine::naming::part_path(&path);
        if state.exists() {
            let saved = mdm_engine::state::ResumeState::load(&state)?;
            if saved.dest != path || saved.url != valid_url(&job.url)? { return Err("Resume metadata does not match job".into()); }
        } else if part.exists() { return Err("Partial file without resume metadata; retained for inspection".into()); }
        let req = Request { url: valid_url(&job.url)?, headers: reqwest::header::HeaderMap::new(), dest: Destination::File(path) };
        let opts = Options { max_connections: job.connections, speed_limit: job.speed_limit, ..Options::default() };
        let shown = job.downloaded;
        job.status = Status::Running;
        job.error = None;
        self.commit(next)?;
        let _guard = self.runtime.enter();
        let d = Download::resume_or_start(state, req, opts, None, shown);
        self.running.insert(id, d);
        Ok(())
    }

    pub fn pause(&mut self, id: u64) -> Result<()> {
        let mut next = self.catalog.clone();
        let job = next.jobs.get_mut(&id).ok_or("Unknown job")?;
        if !self.running.contains_key(&id) { return Err("Job is not running".into()); }
        job.status = Status::Stopping;
        self.commit(next)?;
        self.running.get(&id).ok_or("Job is not running")?.cancel();
        Ok(())
    }

    pub fn set_limit(&mut self, id: u64, limit: Option<u64>) -> Result<()> {
        let mut next = self.catalog.clone();
        let limit = limit.filter(|x| *x > 0);
        next.jobs.get_mut(&id).ok_or("Unknown job")?.speed_limit = limit;
        self.commit(next)?;
        if let Some(d) = self.running.get(&id) { d.set_speed_limit(limit); }
        Ok(())
    }

    pub fn refresh(&mut self) -> Result<()> {
        let mut next = self.catalog.clone();
        let mut finished = Vec::new();
        for (&id, d) in &self.running {
            let s = d.snapshot();
            if matches!(s.state, State::Done | State::Failed | State::Cancelled) {
                let j = next.jobs.get_mut(&id).ok_or("Unknown running job")?;
                j.downloaded = s.downloaded;
                j.total = s.size;
                j.status = match s.state { State::Done => Status::Complete, State::Cancelled => Status::Paused, _ => Status::Failed };
                j.error = if j.status == Status::Failed { s.error } else { None };
                if j.status == Status::Complete {
                    let expected = path_for(&self.root, j);
                    j.completed_name = s.file.as_ref()
                        .filter(|p| p.parent() == expected.parent())
                        .and_then(|p| p.file_name()).and_then(|n| n.to_str())
                        .map(str::to_owned);
                    if j.completed_name.is_none() {
                        j.status = Status::Failed;
                        j.error = Some("Completed file location could not be confirmed".into());
                    }
                }
                finished.push(id);
            }
        }
        if !finished.is_empty() {
            self.commit(next)?;
            for id in finished { self.running.remove(&id); }
        }
        Ok(())
    }

    pub fn list(&mut self) -> Result<Vec<View>> {
        self.refresh()?;
        Ok(self.catalog.jobs.values().rev().map(|job| {
            let mut job = job.clone();
            let mut speed = 0;
            let mut active_connections = 0;
            let mut phase = None;
            if let Some(d) = self.running.get(&job.id) {
                let s = d.snapshot();
                job.downloaded = s.downloaded;
                job.total = s.size;
                speed = s.connections.iter().map(|c| c.speed).sum();
                active_connections = s.connections.iter().filter(|c| matches!(c.status, mdm_engine::download::ConnStatus::Receiving)).count();
                phase = Some(format!("{:?}", s.state));
            }
            let file = if job.status == Status::Complete {
                job.completed_name.as_deref()
                    .filter(|n| mdm_engine::naming::single_file_name(n).as_deref() == Some(*n))
                    .map(|n| self.root.join("files").join(job.id.to_string()).join(n))
            } else { None };
            View { job, speed, active_connections, phase, file }
        }).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_and_persists() {
        let tmp = tempfile::tempdir().unwrap();
        let mut m = Manager::open(tmp.path().into()).unwrap();
        assert!(m.add("file:///etc/passwd", None, 8, None).is_err());
        assert!(m.add("https://example.org/a", Some("../escape"), 8, None).is_err());
        let id = m.add("https://example.org/a.zip", None, 100, None).unwrap();
        assert_eq!(m.list().unwrap()[0].job.connections, 32);
        m.set_limit(id, Some(4096)).unwrap();
        drop(m);
        let mut m = Manager::open(tmp.path().into()).unwrap();
        let jobs = m.list().unwrap();
        assert_eq!(jobs[0].job.name, "a.zip");
        assert_eq!(jobs[0].job.speed_limit, Some(4096));
        assert_eq!(jobs[0].job.status, Status::Queued);
    }
    #[test]
    fn direct_transfer_retains_exact_bytes() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::time::{Duration, Instant};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        listener.set_nonblocking(true).unwrap();
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let server_stop = stop.clone();
        let payload = b"MDM mobile integration test: exact data";
        let server = std::thread::spawn(move || {
            while !server_stop.load(std::sync::atomic::Ordering::Relaxed) {
                let Ok((mut socket, _)) = listener.accept() else { std::thread::sleep(Duration::from_millis(10)); continue };
                socket.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
                let mut request = Vec::new();
                let mut b = [0];
                while request.len() < 16384 && socket.read_exact(&mut b).is_ok() {
                    request.push(b[0]);
                    if request.ends_with(b"\r\n\r\n") { break; }
                }
                let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n", payload.len());
                let _ = socket.write_all(head.as_bytes());
                if !request.starts_with(b"HEAD ") { let _ = socket.write_all(payload); }
            }
        });
        let tmp = tempfile::tempdir().unwrap();
        let mut m = Manager::open(tmp.path().into()).unwrap();
        let id = m.add(&format!("http://{address}/payload.bin"), None, 1, None).unwrap();
        m.start(id).unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        let row = loop {
            let row = m.list().unwrap().remove(0);
            if matches!(row.job.status, Status::Complete | Status::Failed) || Instant::now() >= deadline { break row; }
            std::thread::sleep(Duration::from_millis(50));
        };
        stop.store(true, std::sync::atomic::Ordering::Relaxed);
        server.join().unwrap();
        assert_eq!(row.job.status, Status::Complete, "{:?}", row.job.error);
        assert_eq!(fs::read(row.file.unwrap()).unwrap(), payload);
    }
    #[test]
    fn never_replaces_existing_file() {
        let tmp = tempfile::tempdir().unwrap();
        let mut m = Manager::open(tmp.path().into()).unwrap();
        let id = m.add("https://example.org/a", None, 8, None).unwrap();
        let path = path_for(&m.root, &m.catalog.jobs[&id]);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"keep").unwrap();
        assert!(m.start(id).is_err());
        assert_eq!(fs::read(path).unwrap(), b"keep");
    }
}
