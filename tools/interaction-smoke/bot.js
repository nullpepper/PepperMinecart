// 交互冒烟：用一个真实的离线客户端连进 Paper，按真人操作序列点矿车。
// 只断言玩家可见的结果（背包数量、界面是否打开、容器内容、实体元数据里的显示方块），不看内部状态。
//
// 环境变量：MC_HOST MC_PORT MC_VERSION MC_USER STUB_CLAIM=1（服务端装有 /claimstub 替身时启用保护闸场景）
'use strict'

const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25599)
const VERSION = process.env.MC_VERSION || '1.21.4'
const USER = process.env.MC_USER || 'SmokeBot'
const STUB_CLAIM = process.env.STUB_CLAIM === '1'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const failures = []

function check (name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
  if (!ok) failures.push(name)
}

const bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: USER,
  version: VERSION,
  auth: 'offline'
})

bot.on('kicked', (r) => { console.error('KICKED', JSON.stringify(r)); process.exit(2) })
bot.on('error', (e) => { console.error('ERROR', e); process.exit(2) })

async function cmd (c) { bot.chat('/' + c); await sleep(450) }

function sneak (on) {
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 0 : 1, jumpBoost: 0 })
}

// use_entity：mouse=0 表示"交互"，sneaking 位决定服务端走哪条分支
function useEntity (entity, sneaking) {
  bot._client.write('use_entity', {
    target: entity.id, mouse: 0, sneaking, hand: 0, x: 0, y: 0, z: 0
  })
}

// block_place：对着某个方块的某个面"用一次手上的物品"（铺铁轨、放矿车都走这条）
function placeOn (block, direction) {
  bot._client.write('block_place', {
    hand: 0,
    location: block.position,
    direction,
    cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: 0
  })
}

function count (name) {
  return bot.inventory.items()
    .filter((i) => i.name === name)
    .reduce((a, i) => a + i.count, 0)
}

async function waitFor (predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const v = predicate()
    if (v) return v
    await sleep(100)
  }
  console.log(`TIMEOUT  ${label}`)
  return null
}

function nearestMinecart () {
  return Object.values(bot.entities).find((e) => e.name === 'minecart' && e.position.distanceTo(bot.entity.position) < 8)
}

async function sneakUse (entity, ms = 900) {
  sneak(true)
  await sleep(200)
  useEntity(entity, true)
  await sleep(ms)
  sneak(false)
  await sleep(250)
}

async function lookForWindow (label) {
  useEntity(nearestMinecart() || bot.nearestEntity((e) => e.name === 'minecart'), false)
  return await waitFor(() => bot.currentWindow, 4000, label)
}

function contentsOf (win, name) {
  return win.slots.filter((it) => it && it.name === name).reduce((a, it) => a + it.count, 0)
}

async function main () {
  await waitFor(() => bot.entity, 15000, 'spawn')
  await sleep(2500) // 等区块与实体同步

  // 造一块干净的台子，把人放上去
  await cmd('gamerule fallDamage false')
  await cmd(`tp ${USER} 0 105 0`)
  await sleep(800)
  await cmd('fill -3 100 -3 3 100 3 stone')
  await cmd('fill -3 101 -3 3 104 3 air')
  await cmd(`tp ${USER} 0.5 101 0.5`)
  await sleep(1200)

  const floor = bot.blockAt(new Vec3(2, 100, 0))
  check('平台就位', !!floor && floor.name === 'stone', `blockAt(2,100,0)=${floor && floor.name}`)
  if (!floor) return

  // 物品（先清背包，避免上一轮留在玩家数据里的东西）
  await cmd(`clear ${USER}`)
  await cmd(`give ${USER} rail 1`)
  await cmd(`give ${USER} minecart 1`)
  await cmd(`give ${USER} stone 64`)
  await cmd(`give ${USER} cartography_table 1`)
  const gotItems = await waitFor(
    () => count('rail') === 1 && count('minecart') === 1 && count('stone') === 64 && count('cartography_table') === 1,
    6000, 'items')
  check('拿到物品', !!gotItems,
    `rail=${count('rail')} minecart=${count('minecart')} stone=${count('stone')} table=${count('cartography_table')}`)

  // 1) 铺铁轨：原版 MinecartItem#useOn 要求点中的方块属于 BlockTags.RAILS
  const railItem = bot.inventory.items().find((i) => i.name === 'rail')
  await bot.equip(railItem, 'hand')
  await sleep(300)
  await bot.lookAt(new Vec3(2.5, 101.0, 0.5), true)
  placeOn(floor, 1)
  const railBlock = await waitFor(() => {
    const b = bot.blockAt(new Vec3(2, 101, 0))
    return b && b.name === 'rail' ? b : null
  }, 4000, 'rail placed')
  check('铁轨铺好', !!railBlock, railBlock ? railBlock.name : '')
  if (!railBlock) return

  // 2) 把空矿车放到铁轨上
  const cartItem = bot.inventory.items().find((i) => i.name === 'minecart')
  await bot.equip(cartItem, 'hand')
  await sleep(300)
  await bot.lookAt(new Vec3(2.5, 101.1, 0.5), true)
  placeOn(railBlock, 1)
  const cart = await waitFor(nearestMinecart, 5000, 'minecart spawn')
  check('矿车生成', !!cart, cart ? `at ${cart.position}` : '')
  if (!cart) return
  await sleep(600)
  check('放矿车后物品消耗', count('minecart') === 0, `minecart=${count('minecart')}`)

  // 3) 潜行右键：把石头装到矿车上
  await bot.equip(bot.inventory.items().find((i) => i.name === 'stone'), 'hand')
  await sleep(300)
  await sneakUse(cart)
  check('潜行右键放置：石头 -1', count('stone') === 63, `stone=${count('stone')}`)
  const metaPlaced = JSON.stringify(cart.metadata)

  // 4) 再潜行右键：取回石头
  await sleep(700)
  await sneakUse(cart)
  check('潜行右键取下：石头回到 64', count('stone') === 64, `stone=${count('stone')}`)
  const metaAfterTakeOff = JSON.stringify(cart.metadata)
  check('显示方块随元数据一起消失', metaAfterTakeOff !== metaPlaced,
    metaAfterTakeOff === metaPlaced ? 'metadata 未变化' : '')

  // 5) 工作站：装制图台 -> 开原版界面 -> 取下
  await sleep(700)
  await bot.equip(bot.inventory.items().find((i) => i.name === 'cartography_table'), 'hand')
  await sleep(300)
  await sneakUse(cart)
  check('工作站装到矿车上', count('cartography_table') === 0, `table=${count('cartography_table')}`)

  await sleep(800)
  const win = await lookForWindow('workstation window')
  check('右键打开界面', !!win, win ? `type=${win.type} slots=${win.slots.length}` : '')
  if (win) { bot.closeWindow(win); await sleep(600) }

  await sneakUse(cart)
  check('工作站取下回到背包', count('cartography_table') === 1, `table=${count('cartography_table')}`)

  // 6) 领地保护：替身门面拒绝时方块不许离开背包
  if (STUB_CLAIM) {
    await sleep(600)
    const st = bot.inventory.items().find((i) => i.name === 'stone')
    if (st) {
      await bot.equip(st, 'hand')
      await cmd('claimstub on')
      await sleep(300)
      await sneakUse(cart)
      check('保护拒绝：石头没有离开背包', count('stone') === 64, `stone=${count('stone')}`)

      await cmd('claimstub off')
      await sleep(300)
      await sneakUse(cart)
      check('解除拒绝后放置成功', count('stone') === 63, `stone=${count('stone')}`)

      await sneakUse(cart)
      check('再取下回到 64', count('stone') === 64, `stone=${count('stone')}`)
    }
  }

  // 7) 容器：木桶装上矿车 -> 塞东西 -> 关界面 -> 取下 -> 装回 -> 内容还在
  await sleep(600)
  await cmd(`clear ${USER}`)
  await cmd(`give ${USER} barrel 1`)
  await cmd(`give ${USER} stone 8`)
  await waitFor(() => count('barrel') === 1 && count('stone') === 8, 5000, 'container items')

  await bot.equip(bot.inventory.items().find((i) => i.name === 'barrel'), 'hand')
  await sleep(300)
  await sneakUse(cart)
  check('木桶装到矿车上', count('barrel') === 0, `barrel=${count('barrel')}`)

  await sleep(700)
  const barrelWin = await lookForWindow('barrel window')
  check('木桶界面打开', !!barrelWin, barrelWin ? `type=${barrelWin.type} slots=${barrelWin.slots.length}` : '')
  if (barrelWin) {
    const stoneSlot = barrelWin.slots.findIndex((it) => it && it.name === 'stone')
    check('背包里的石头在窗口中可见', stoneSlot >= 0, `slot=${stoneSlot}`)
    if (stoneSlot >= 0) {
      await bot.clickWindow(stoneSlot, 0, 1) // shift-click 整叠塞进容器
      await sleep(800)
    }
    bot.closeWindow(barrelWin)
    await sleep(700)
  }
  check('石头塞进容器后背包清空', count('stone') === 0, `stone=${count('stone')}`)

  await sleep(600)
  const barrelWin2 = await lookForWindow('barrel window 2')
  check('重新打开后内容还在', barrelWin2 ? contentsOf(barrelWin2, 'stone') === 8 : false,
    barrelWin2 ? `inside=${contentsOf(barrelWin2, 'stone')}` : '')
  if (barrelWin2) { bot.closeWindow(barrelWin2); await sleep(700) }

  await sneakUse(cart)
  check('木桶取下回到背包', count('barrel') === 1, `barrel=${count('barrel')}`)

  // 8) 装回同一辆矿车，内容应当跟着方块物品回来
  await sleep(700)
  await bot.equip(bot.inventory.items().find((i) => i.name === 'barrel'), 'hand')
  await sleep(300)
  await sneakUse(cart)
  check('木桶重新装回', count('barrel') === 0, `barrel=${count('barrel')}`)

  await sleep(700)
  const barrelWin3 = await lookForWindow('barrel window 3')
  check('装回后内容没丢', barrelWin3 ? contentsOf(barrelWin3, 'stone') === 8 : false,
    barrelWin3 ? `inside=${contentsOf(barrelWin3, 'stone')}` : '')
  if (barrelWin3) { bot.closeWindow(barrelWin3); await sleep(600) }

  await cmd('kill @e[type=minecart,limit=5]')
  await sleep(400)
}

main()
  .catch((e) => { console.error('HARNESS ERROR', e); failures.push('harness-error') })
  .finally(async () => {
    console.log(failures.length === 0 ? 'RESULT OK' : `RESULT FAILED (${failures.length}): ${failures.join(', ')}`)
    await sleep(300)
    try { bot.quit() } catch (e) {}
    process.exit(failures.length === 0 ? 0 : 1)
  })
