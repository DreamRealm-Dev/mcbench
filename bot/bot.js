#!/usr/bin/env node
/**
 * MCBench 纯客户端假人 Bot（Mineflayer）
 *
 * 用法：
 *   node bot.js --host 127.0.0.1 --port 25565 --count 5 --prefix MCBenchBot \
 *       --duration 30 --action walk
 *
 * 动作：
 *   walk  - 自动前进 + 随机跳跃，模拟真实玩家移动
 *   mine  - 前进并尝试挖掘面前的方块
 *
 * 说明：这是“纯客户端协议 Bot”方案，不依赖服务端安装 mcbench。
 * 每个 bot 都是一个独立 Minecraft 客户端连接，服务端看到的就是普通玩家。
 */
import mineflayer from 'mineflayer';

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i++) {
    const key = argv[i];
    if (key.startsWith('--')) {
      const name = key.slice(2);
      const value = argv[i + 1];
      args[name] = value !== undefined && !value.startsWith('--') ? value : true;
      if (value !== undefined && !value.startsWith('--')) i++;
    }
  }
  return args;
}

const args = parseArgs(process.argv);
const host = args.host || '127.0.0.1';
const port = Number(args.port || 25565);
const count = Math.max(1, Number(args.count || 1));
const prefix = args.prefix || 'MCBenchBot';
const duration = Math.max(1, Number(args.duration || 30));
const action = args.action || 'walk';

const bots = [];
let spawned = 0;

console.log(`[mcbench-bot] 启动 ${count} 个 Mineflayer 假人 -> ${host}:${port} action=${action}`);

for (let i = 0; i < count; i++) {
  const username = `${prefix}${i}`;
  const bot = mineflayer.createBot({
    host,
    port,
    username,
    auth: 'offline',
    version: false, // 自动协商
  });

  bot.once('spawn', () => {
    spawned++;
    console.log(`[mcbench-bot] ${username} 已进入服务器 (${spawned}/${count})`);
    bot.setControlState('forward', true);

    if (action === 'mine') {
      const timer = setInterval(() => {
        const block = bot.blockAtCursor(4);
        if (block) {
          bot.dig(block).catch((err) => {
            if (err) console.log(`[mcbench-bot] ${username} 挖掘失败: ${err.message}`);
          });
        }
      }, 3000);
      bot._mcbenchMineTimer = timer;
    }

    // 随机跳跃，模拟真实玩家
    const jumpTimer = setInterval(() => {
      bot.setControlState('jump', true);
      setTimeout(() => bot.setControlState('jump', false), 250);
    }, 2000 + Math.random() * 3000);
    bot._mcbenchJumpTimer = jumpTimer;
  });

  bot.on('error', (err) => {
    console.log(`[mcbench-bot] ${username} 错误: ${err.message}`);
  });

  bot.on('end', (reason) => {
    console.log(`[mcbench-bot] ${username} 断开: ${reason || 'end'}`);
    if (bot._mcbenchJumpTimer) clearInterval(bot._mcbenchJumpTimer);
    if (bot._mcbenchMineTimer) clearInterval(bot._mcbenchMineTimer);
  });

  bots.push(bot);
}

setTimeout(() => {
  console.log(`[mcbench-bot] ${duration}s 结束，退出全部假人`);
  for (const bot of bots) {
    try {
      bot.quit();
    } catch (e) {
      // ignore
    }
  }
  setTimeout(() => process.exit(0), 2000);
}, duration * 1000);
