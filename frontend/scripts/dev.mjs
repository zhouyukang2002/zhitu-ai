// 同时启动 Mock 服务 (3001) 与 Vite Dev Server (5173)，Ctrl+C 一并退出
import { spawn } from 'node:child_process'

const children = []

function start(name, cmd, args, color) {
  const child = spawn(cmd, args, { shell: true, env: process.env })
  const tag = `\x1b[${color}m[${name}]\x1b[0m`
  const pipe = (stream) => stream.on('data', (d) => {
    String(d).split('\n').filter(Boolean).forEach((l) => console.log(`${tag} ${l}`))
  })
  pipe(child.stdout)
  pipe(child.stderr)
  child.on('exit', (code) => {
    if (code !== null && code !== 0) {
      console.error(`${tag} exited with code ${code}`)
      shutdown()
    }
  })
  children.push(child)
}

function shutdown() {
  for (const c of children) {
    try { c.kill('SIGTERM') } catch { /* noop */ }
  }
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

start('mock', 'node', ['mock/server.mjs'], '33')
start('vite', 'npx', ['vite'], '36')
