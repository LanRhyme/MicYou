/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use std::sync::Arc;
use tokio::sync::{oneshot, Mutex, OwnedMutexGuard};
use tokio::task::JoinHandle;

pub const STARTUP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);
pub const AUDIO_JOIN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ServerLifecyclePhase {
    #[default]
    Stopped,
    Starting,
    Running,
    Stopping,
    StoppingResidual,
}

enum AudioThreadState {
    None,
    Owned(std::thread::JoinHandle<()>),
    Joining(JoinHandle<Result<(), String>>),
}

pub struct ServerLifecycleState {
    phase: ServerLifecyclePhase,
    audio_thread: AudioThreadState,
}

impl Default for ServerLifecycleState {
    fn default() -> Self {
        Self {
            phase: ServerLifecyclePhase::Stopped,
            audio_thread: AudioThreadState::None,
        }
    }
}

pub async fn await_startup_ready(
    ready: oneshot::Receiver<Result<(), String>>,
    component: &str,
    timeout_duration: std::time::Duration,
) -> Result<(), String> {
    match tokio::time::timeout(timeout_duration, ready).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => Err(format!("{} exited during startup", component)),
        Err(_) => Err(format!("{} startup timed out", component)),
    }
}

#[derive(Clone, Default)]
pub struct ServerLifecycleGate {
    lock: Arc<Mutex<()>>,
}

impl ServerLifecycleGate {
    pub async fn enter(&self) -> OwnedMutexGuard<()> {
        self.lock.clone().lock_owned().await
    }
}

impl ServerLifecycleState {
    pub async fn begin_start(&mut self) -> Result<(), String> {
        if let AudioThreadState::Joining(join) = &mut self.audio_thread {
            if !join.is_finished() {
                return Err(
                    "Server is still stopping: the previous audio thread has not exited"
                        .to_string(),
                );
            }
            let result = join
                .await
                .map_err(|error| format!("Audio cleanup task failed: {}", error))?;
            self.audio_thread = AudioThreadState::None;
            self.phase = ServerLifecyclePhase::Stopped;
            result?;
        }
        if self.phase != ServerLifecyclePhase::Stopped {
            return Err(format!(
                "Server cannot start while lifecycle is {:?}",
                self.phase
            ));
        }
        self.phase = ServerLifecyclePhase::Starting;
        Ok(())
    }

    pub fn set_audio_thread(&mut self, thread: std::thread::JoinHandle<()>) {
        self.audio_thread = AudioThreadState::Owned(thread);
    }

    pub fn mark_running(&mut self) {
        self.phase = ServerLifecyclePhase::Running;
    }

    pub fn begin_stopping(&mut self) {
        self.phase = ServerLifecyclePhase::Stopping;
    }

    pub fn mark_stopped_without_audio(&mut self) {
        if matches!(self.audio_thread, AudioThreadState::None) {
            self.phase = ServerLifecyclePhase::Stopped;
        }
    }

    pub async fn join_audio_bounded(
        &mut self,
        timeout_duration: std::time::Duration,
    ) -> Result<(), String> {
        if let AudioThreadState::Owned(thread) =
            std::mem::replace(&mut self.audio_thread, AudioThreadState::None)
        {
            self.audio_thread = AudioThreadState::Joining(tokio::task::spawn_blocking(move || {
                thread
                    .join()
                    .map_err(|_| "Audio thread panicked during shutdown".to_string())
            }));
        }

        let AudioThreadState::Joining(join) = &mut self.audio_thread else {
            self.phase = ServerLifecyclePhase::Stopped;
            return Ok(());
        };
        match tokio::time::timeout(timeout_duration, &mut *join).await {
            Ok(result) => {
                let result =
                    result.map_err(|error| format!("Audio cleanup task failed: {}", error))?;
                self.audio_thread = AudioThreadState::None;
                self.phase = ServerLifecyclePhase::Stopped;
                result
            }
            Err(_) => {
                self.phase = ServerLifecyclePhase::StoppingResidual;
                Err("Server stopped with residual audio cleanup: audio thread did not exit within 3 seconds".to_string())
            }
        }
    }

    pub fn phase(&self) -> ServerLifecyclePhase {
        self.phase
    }
}

#[cfg(test)]
mod tests {
    use super::{
        await_startup_ready, ServerLifecycleGate, ServerLifecyclePhase, ServerLifecycleState,
    };
    use std::sync::Arc;
    use tokio::sync::Notify;

    #[tokio::test]
    async fn lifecycle_gate_serializes_complete_transactions() {
        let gate = ServerLifecycleGate::default();
        let first_entered = Arc::new(Notify::new());
        let release_first = Arc::new(Notify::new());
        let second_entered = Arc::new(Notify::new());

        let first = {
            let gate = gate.clone();
            let first_entered = first_entered.clone();
            let release_first = release_first.clone();
            tokio::spawn(async move {
                let _guard = gate.enter().await;
                first_entered.notify_one();
                release_first.notified().await;
            })
        };
        first_entered.notified().await;

        let second = {
            let gate = gate.clone();
            let second_entered = second_entered.clone();
            tokio::spawn(async move {
                let _guard = gate.enter().await;
                second_entered.notify_one();
            })
        };

        assert!(
            tokio::time::timeout(
                std::time::Duration::from_millis(50),
                second_entered.notified()
            )
            .await
            .is_err(),
            "a second lifecycle transaction entered before the first completed"
        );

        release_first.notify_one();
        tokio::time::timeout(std::time::Duration::from_secs(1), second_entered.notified())
            .await
            .expect("the second lifecycle transaction did not enter after release");

        first.await.unwrap();
        second.await.unwrap();
    }

    #[tokio::test]
    async fn startup_ready_times_out_with_component_name() {
        let (_sender, receiver) = tokio::sync::oneshot::channel();
        let error = await_startup_ready(
            receiver,
            "Audio output",
            std::time::Duration::from_millis(20),
        )
        .await
        .unwrap_err();
        assert_eq!(error, "Audio output startup timed out");
    }

    #[tokio::test]
    async fn residual_audio_thread_blocks_restart_until_it_exits() {
        let release = Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()));
        let thread_release = release.clone();
        let thread = std::thread::spawn(move || {
            let (lock, condition) = &*thread_release;
            let mut released = lock.lock().unwrap();
            while !*released {
                released = condition.wait(released).unwrap();
            }
        });
        let mut lifecycle = ServerLifecycleState::default();
        lifecycle.begin_start().await.unwrap();
        lifecycle.set_audio_thread(thread);
        lifecycle.begin_stopping();

        let error = lifecycle
            .join_audio_bounded(std::time::Duration::from_millis(20))
            .await
            .unwrap_err();
        assert!(error.contains("residual audio cleanup"));
        assert_eq!(lifecycle.phase(), ServerLifecyclePhase::StoppingResidual);
        assert!(lifecycle.begin_start().await.is_err());

        let (lock, condition) = &*release;
        *lock.lock().unwrap() = true;
        condition.notify_one();
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        lifecycle.begin_start().await.unwrap();
        assert_eq!(lifecycle.phase(), ServerLifecyclePhase::Starting);
    }
}
