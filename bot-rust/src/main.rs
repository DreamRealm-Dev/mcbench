//! MCBench 纯客户端假人 Bot（Azalea/Rust）
//!
//! 用法:
//!   mcbench-bot-rust --host 127.0.0.1 --port 25565 --username MCBenchBot0 --duration 30 --action walk
//!
//! 说明:
//!   - 每个进程连接一个假人，Java 侧 FakeClientBridge 可按数量启动多个进程。
//!   - 使用 Azalea 实现纯客户端协议，不依赖服务端安装 mcbench。
//!   - action=walk 自动前进+跳跃; action=mine 暂未实现（可后续补充）。

use azalea::{prelude::*, Account, Client, Event, WalkDirection};
use std::env;
use std::time::Duration;

#[derive(Default, Clone, Component)]
struct State;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = env::args().collect();
    let mut host = "127.0.0.1".to_string();
    let mut port = "25565".to_string();
    let mut username = "MCBenchBot".to_string();
    let mut duration = 30u64;
    let mut _action = "walk".to_string();

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--host" => {
                i += 1;
                if i < args.len() {
                    host = args[i].clone();
                }
            }
            "--port" => {
                i += 1;
                if i < args.len() {
                    port = args[i].clone();
                }
            }
            "--username" => {
                i += 1;
                if i < args.len() {
                    username = args[i].clone();
                }
            }
            "--duration" => {
                i += 1;
                if i < args.len() {
                    duration = args[i].parse().unwrap_or(30);
                }
            }
            "--action" => {
                i += 1;
                if i < args.len() {
                    _action = args[i].clone();
                }
            }
            _ => {}
        }
        i += 1;
    }

    let address = format!("{host}:{port}");
    println!("[mcbench-bot-rust] {username} -> {address} duration={duration}s");

    SwarmBuilder::new()
        .add_account(Account::offline(&username))
        .set_handler(handle)
        .start(address)
        .await?;

    Ok(())
}

async fn handle(mut bot: Client, event: Event, _state: State) -> anyhow::Result<()> {
    match event {
        Event::Login => {
            println!("[mcbench-bot-rust] {} 已进入服务器", bot.profile.name);
            bot.walk(WalkDirection::Forward);
            bot.set_jumping(true);

            // 定时退出：每个 bot 进程运行 duration 秒后断开并退出
            let duration = std::env::args()
                .collect::<Vec<_>>()
                .iter()
                .position(|a| a == "--duration")
                .and_then(|idx| std::env::args().nth(idx + 1))
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(30);
            let bot_for_exit = bot.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(duration)).await;
                println!("[mcbench-bot-rust] 时间到，断开 {}", bot_for_exit.profile.name);
                bot_for_exit.disconnect();
                tokio::time::sleep(Duration::from_millis(500)).await;
                std::process::exit(0);
            });
        }
        Event::Disconnect(reason) => {
            if let Some(reason) = reason {
                println!("[mcbench-bot-rust] 被断开: {}", reason.to_ansi());
            } else {
                println!("[mcbench-bot-rust] 已断开");
            }
        }
        _ => {}
    }

    Ok(())
}
