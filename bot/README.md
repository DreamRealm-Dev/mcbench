# MCBench 纯客户端假人 Bot（Mineflayer）

这是 mcbench 的“假客户端”参考实现：使用 [Mineflayer](https://github.com/PrismarineJS/mineflayer) 以纯客户端协议方式连接服务器，不需要服务端安装 mcbench。

## 安装

需要 Node.js 18+：

```bash
cd bot
npm install
```

## 运行

```bash
node bot.js --host 127.0.0.1 --port 25565 --count 5 --prefix MCBenchBot --duration 30 --action walk
```

参数：
- `--host`：服务器 IP，默认 `127.0.0.1`
- `--port`：服务器端口，默认 `25565`
- `--count`：假人数量，默认 `1`
- `--prefix`：假人用户名前缀，默认 `MCBenchBot`
- `--duration`：运行秒数，默认 `30`
- `--action`：`walk`（自动前进+跳跃）或 `mine`（前进并挖掘面前方块）

## 说明

- 每个假人都是独立 Minecraft 客户端连接，服务端看到的就是普通玩家。
- 当前为离线模式（`auth: 'offline'`），适合本地/离线服务器；正版服务器需要扩展 `auth: 'microsoft'` 或 `auth: 'mojang'` 配置。
- 本目录是独立 Node.js 模块，不参与 Java 构建。

## 许可证与内置

Mineflayer 使用 **MIT License**，允许再分发和内置，但必须保留版权与许可证文本。
本仓库已在 `NOTICE.md` 中包含 Mineflayer 的 MIT 许可证全文。

当前 `node_modules` 体积约 446MB，不适合直接塞进 Java mod jar。
可选的内置方案：
1. 发布独立的 `mcbench-bot.zip`，用户解压到 `config/mcbench-bot/` 即可使用。
2. 后续用 Rust Azalea 编译成单文件二进制，体积更小，更适合随 mod 分发。
