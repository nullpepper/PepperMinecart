// 交互冒烟：用一个真实的离线客户端连进 Paper，按真人操作序列点矿车。
// 只断言玩家可见的结果（背包数量、界面类型、容器内容、实体元数据里的显示方块），不看内部状态。
//
// 环境变量：
//   MC_HOST MC_PORT MC_VERSION MC_USER   连接参数
//   STUB_CLAIM=1   服务端装有 PepperClaim 替身（/claimstub on|off）时，加测领地保护闸
//   STUB_HOOKS=1   服务端装有 SmokeHooks 替身（/smokehook on|off）时，加测「交付被取消」
//   DEBUG=1        打印矿车实体 NBT 之类的诊断信息
'use strict'

const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25599)
const VERSION = process.env.MC_VERSION || '1.21.4'
const USER = process.env.MC_USER || 'SmokeBot'
const STUB_CLAIM = process.env.STUB_CLAIM === '1'
const STUB_HOOKS = process.env.STUB_HOOKS === '1'
const DEBUG = process.env.DEBUG === '1'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const failures = []

function check (name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + (ok ? '' : '(' + detail + ')') : ''}`)
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
if (DEBUG) bot.on('messagestr', (m) => console.log('CHAT  ' + m.slice(0, 240)))

async function cmd (c) { bot.chat('/' + c); await sleep(450) }

async function give (name, amount = 1) {
  await cmd(`give ${USER} ${name} ${amount}`)
}

function sneak (on) {
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 0 : 1, jumpBoost: 0 })
}

// use_entity：mouse=0 表示「交互」，sneaking 位决定服务端走哪条分支
function useEntity (entity, sneaking) {
  bot._client.write('use_entity', {
    target: entity.id, mouse: 0, sneaking, hand: 0, x: 0, y: 0, z: 0
  })
}

// block_place：对着某个方块的某个面「用一次手上的物品」（铺铁轨、放矿车都走这条）
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

const ANY_CART = ['minecart', 'chest_minecart', 'hopper_minecart', 'furnace_minecart', 'tnt_minecart', 'command_block_minecart']

function cartEntity (names = ANY_CART) {
  return Object.values(bot.entities).find((e) => names.includes(e.name) && e.position.distanceTo(bot.entity.position) < 10)
}

function contentsOf (win, name) {
  return win.slots.filter((it) => it && it.name === name).reduce((a, it) => a + it.count, 0)
}

async function equipItem (name) {
  const item = bot.inventory.items().find((i) => i.name === name)
  if (!item) { console.log(`SKIP  背包里没有 ${name}`); return false }
  await bot.equip(item, 'hand')
  await sleep(350)
  return true
}

async function sneakUse (entity) {
  if (bot.vehicle) { try { await bot.dismount() } catch (e) {} }
  sneak(true)
  await sleep(220)
  useEntity(entity, true)
  await sleep(700)
  sneak(false)
  await sleep(250)
}

/**
 * 潜行右键把方块装到矿车上，直到断言成立。
 * 单次点击偶尔会被服务端吞掉（交互冷却、实体还在客户端同步中），这里按玩家可见结果重试。
 */
async function attach (name, { cartNames = ANY_CART, expect, tries = 4 } = {}) {
  const before = count(name)
  if (!expect) expect = () => count(name) < before
  for (let i = 1; i <= tries; i++) {
    if (!await equipItem(name)) return false
    const cart = cartEntity(cartNames)
    if (!cart) { console.log(`SKIP  attach(${name})：找不到矿车`); return false }
    await sneakUse(cart)
    await sleep(350)
    if (expect()) {
      if (i > 1) console.log(`NOTE  attach(${name}) 第 ${i} 次才成功`)
      return true
    }
  }
  return false
}

/** 潜行右键取下，直到断言成立。 */
async function takeOff (name, { cartNames = ANY_CART, expect, tries = 4 } = {}) {
  const before = count(name)
  if (!expect) expect = () => count(name) > before
  for (let i = 1; i <= tries; i++) {
    const cart = cartEntity(cartNames)
    if (!cart) { console.log(`SKIP  takeOff(${name})：找不到矿车`); return false }
    await sneakUse(cart)
    await sleep(350)
    if (expect()) {
      if (i > 1) console.log(`NOTE  takeOff(${name}) 第 ${i} 次才成功`)
      return true
    }
  }
  return false
}

/** 普通右键打开界面（工作站/容器），同样允许重试。 */
async function openWindow (names, label, tries = 4) {
  for (let i = 1; i <= tries; i++) {
    const cart = cartEntity(names)
    if (!cart) { console.log(`SKIP  openWindow(${label})：找不到矿车`); return null }
    useEntity(cart, false)
    const win = await waitFor(() => bot.currentWindow, 3000, `${label} (${i})`)
    if (win) return win
  }
  return null
}

/** 杀掉现有矿车与掉落物，清空背包，重新放一辆干净的空矿车到铁轨上。 */
async function freshCart () {
  await cmd('kill @e[type=minecart]')
  await cmd('kill @e[type=item]')
  await cmd(`clear ${USER}`)
  await sleep(700)
  await cmd('setblock 2 100 0 minecraft:stone')
  await cmd('setblock 2 101 0 minecraft:rail')
  await give('minecart')
  await sleep(900)
  if (!await equipItem('minecart')) return null
  await bot.lookAt(new Vec3(2.5, 101.1, 0.5), true)
  placeOn(bot.blockAt(new Vec3(2, 101, 0)), 1)
  const cart = await waitFor(() => cartEntity(['minecart']), 5000, 'fresh minecart')
  await sleep(500)
  return cart
}

/** 场上现有的掉落物实体数量。 */
function itemCount () {
  return Object.values(bot.entities).filter((e) => e.name === 'item').length
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
  await sleep(1500)

  const floor = bot.blockAt(new Vec3(2, 100, 0))
  check('平台就位', !!floor && floor.name === 'stone', `blockAt(2,100,0)=${floor && floor.name}`)
  if (!floor) return

  await cmd(`clear ${USER}`)
  await give('rail')
  await give('minecart')
  await give('stone', 64)
  await give('cartography_table')
  await sleep(1200)
  check('拿到物品', count('rail') === 1 && count('minecart') === 1 && count('stone') === 64 && count('cartography_table') === 1,
    `rail=${count('rail')} minecart=${count('minecart')} stone=${count('stone')} table=${count('cartography_table')}`)

  // 1) 铺铁轨：原版 MinecartItem#useOn 要求点中的方块属于 BlockTags.RAILS
  await equipItem('rail')
  await bot.lookAt(new Vec3(2.5, 101.0, 0.5), true)
  placeOn(floor, 1)
  const railBlock = await waitFor(() => {
    const b = bot.blockAt(new Vec3(2, 101, 0))
    return b && b.name === 'rail' ? b : null
  }, 4000, 'rail placed')
  check('铁轨铺好', !!railBlock, railBlock ? railBlock.name : '')
  if (!railBlock) return

  // 2) 把空矿车放到铁轨上
  await equipItem('minecart')
  await bot.lookAt(new Vec3(2.5, 101.1, 0.5), true)
  placeOn(railBlock, 1)
  const cart = await waitFor(() => cartEntity(['minecart']), 5000, 'minecart spawn')
  check('矿车生成', !!cart, cart ? `at ${cart.position}` : '')
  if (!cart) return
  await sleep(600)
  check('放矿车后物品消耗', count('minecart') === 0, `minecart=${count('minecart')}`)

  // 3) 潜行右键：把石头装到矿车上 / 再取回
  await attach('stone')
  check('潜行右键放置：石头 -1', count('stone') === 63, `stone=${count('stone')}`)
  const metaPlaced = JSON.stringify(cartEntity(['minecart'])?.metadata)

  await takeOff('stone')
  check('潜行右键取下：石头回到 64', count('stone') === 64, `stone=${count('stone')}`)
  const metaAfterTakeOff = JSON.stringify(cartEntity(['minecart'])?.metadata)
  check('显示方块随元数据一起消失', metaAfterTakeOff !== metaPlaced,
    metaAfterTakeOff === metaPlaced ? 'metadata 未变化' : '')

  // 4) 工作站：装制图台 -> 开原版界面 -> 取下
  await attach('cartography_table')
  check('工作站装到矿车上', count('cartography_table') === 0, `table=${count('cartography_table')}`)

  const win = await openWindow(['minecart'], 'workstation window')
  check('右键打开界面', !!win, win ? `type=${win.type} slots=${win.slots.length}` : '')
  if (win) { bot.closeWindow(win); await sleep(600) }

  await takeOff('cartography_table')
  check('工作站取下回到背包', count('cartography_table') === 1, `table=${count('cartography_table')}`)

  // 5) 领地保护：替身门面拒绝时方块不许离开背包
  if (STUB_CLAIM) {
    await sleep(600)
    await equipItem('stone')
    await cmd('claimstub on')
    await sleep(400)
    await sneakUse(cartEntity(['minecart']))
    check('保护拒绝：石头没有离开背包', count('stone') === 64, `stone=${count('stone')}`)

    await cmd('claimstub off')
    await sleep(400)
    await attach('stone')
    check('解除拒绝后放置成功', count('stone') === 63, `stone=${count('stone')}`)

    await takeOff('stone')
    check('再取下回到 64', count('stone') === 64, `stone=${count('stone')}`)
  }

  // 6) 容器：木桶装上矿车 -> 塞东西 -> 关界面 -> 取下 -> 装回 -> 内容还在
  if (!await freshCart()) return
  await give('barrel')
  await give('stone', 8)
  await sleep(1000)
  await attach('barrel')
  check('木桶装到矿车上', count('barrel') === 0, `barrel=${count('barrel')}`)

  const barrelWin = await openWindow(['minecart'], 'barrel window')
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

  const barrelWin2 = await openWindow(['minecart'], 'barrel window 2')
  check('重新打开后内容还在', barrelWin2 ? contentsOf(barrelWin2, 'stone') === 8 : false,
    barrelWin2 ? `inside=${contentsOf(barrelWin2, 'stone')}` : '')
  if (barrelWin2) { bot.closeWindow(barrelWin2); await sleep(700) }

  await takeOff('barrel')
  check('木桶取下回到背包', count('barrel') === 1, `barrel=${count('barrel')}`)

  await attach('barrel')
  check('木桶重新装回', count('barrel') === 0, `barrel=${count('barrel')}`)

  const barrelWin3 = await openWindow(['minecart'], 'barrel window 3')
  check('装回后内容没丢', barrelWin3 ? contentsOf(barrelWin3, 'stone') === 8 : false,
    barrelWin3 ? `inside=${contentsOf(barrelWin3, 'stone')}` : '')
  if (barrelWin3) { bot.closeWindow(barrelWin3); await sleep(700) }

  await takeOff('barrel')

  // 7) 原版矿车：箱子 -> 箱子矿车（内容进实体库存）-> 取下变回方块物品 -> 装回内容还在
  if (!await freshCart()) return
  await give('chest')
  await give('stone', 4)
  await sleep(1000)
  await attach('chest', { expect: () => count('chest') === 0 })
  const chestCart = await waitFor(() => cartEntity(['chest_minecart']), 4000, 'chest minecart')
  check('箱子把矿车换成箱子矿车', !!chestCart, chestCart ? `type=${chestCart.name}` : '')

  const chestWin = await openWindow(['chest_minecart'], 'chest window')
  check('箱子矿车界面打开', !!chestWin && chestWin.slots.length >= 27,
    chestWin ? `type=${chestWin.type} slots=${chestWin.slots.length}` : '')
  if (chestWin) {
    const slot = chestWin.slots.findIndex((it) => it && it.name === 'stone')
    if (slot >= 0) { await bot.clickWindow(slot, 0, 1); await sleep(800) }
    bot.closeWindow(chestWin)
    await sleep(700)
  }
  check('石头进了箱子矿车', count('stone') === 0, `stone=${count('stone')}`)

  await takeOff('chest', { cartNames: ['chest_minecart'] })
  const rideableBack = await waitFor(() => cartEntity(['minecart']), 4000, 'rideable minecart back')
  check('取下后变回普通矿车', !!rideableBack, rideableBack ? rideableBack.name : '')
  check('箱子回到背包', count('chest') === 1, `chest=${count('chest')}`)

  await attach('chest')
  const chestCart2 = await waitFor(() => cartEntity(['chest_minecart']), 4000, 'chest minecart 2')
  check('箱子矿车重新装回', !!chestCart2, chestCart2 ? chestCart2.name : '')
  const chestWin2 = await openWindow(['chest_minecart'], 'chest window 2')
  check('箱子里的东西没丢', chestWin2 ? contentsOf(chestWin2, 'stone') === 4 : false,
    chestWin2 ? `inside=${contentsOf(chestWin2, 'stone')}` : '')
  if (chestWin2) { bot.closeWindow(chestWin2); await sleep(700) }
  await takeOff('chest', { cartNames: ['chest_minecart'] })

  // 8) 漏斗、熔炉：各自变成对应的原版矿车，取下回到方块
  for (const [block, entity] of [['hopper', 'hopper_minecart'], ['furnace', 'furnace_minecart']]) {
    if (!await freshCart()) return
    await give(block)
    await sleep(900)
    await attach(block, { expect: () => count(block) === 0 })
    const converted = await waitFor(() => cartEntity([entity]), 4000, entity)
    check(`${block} 变成 ${entity}`, !!converted, converted ? converted.name : '')
    await takeOff(block, { cartNames: [entity] })
    check(`${block} 取下回到背包`, count(block) === 1, `${block}=${count(block)}`)
  }

  // 9) 铁砧：装上去开原版铁砧界面
  if (!await freshCart()) return
  await give('anvil')
  await sleep(900)
  await attach('anvil')
  check('铁砧装到矿车上', count('anvil') === 0, `anvil=${count('anvil')}`)
  const anvilWin = await openWindow(['minecart'], 'anvil window')
  check('铁砧界面打开', !!anvilWin && anvilWin.type === 'minecraft:anvil', anvilWin ? `type=${anvilWin.type}` : '')
  if (anvilWin) { bot.closeWindow(anvilWin); await sleep(600) }
  await takeOff('anvil')
  check('铁砧取下回到背包', count('anvil') === 1, `anvil=${count('anvil')}`)

  // 10) 发射器：装满箭，放上充能的激活铁轨，车一动就弹出一件
  if (!await freshCart()) return
  await give('dispenser')
  await give('arrow', 8)
  await sleep(1000)
  await attach('dispenser')
  check('发射器装到矿车上', count('dispenser') === 0, `dispenser=${count('dispenser')}`)
  const dispWin = await openWindow(['minecart'], 'dispenser window')
  check('发射器界面打开', !!dispWin, dispWin ? `type=${dispWin.type} slots=${dispWin.slots.length}` : '')
  if (dispWin) {
    const slot = dispWin.slots.findIndex((it) => it && it.name === 'arrow')
    if (slot >= 0) { await bot.clickWindow(slot, 0, 1); await sleep(800) }
    bot.closeWindow(dispWin)
    await sleep(700)
  }
  check('箭进了发射器', count('arrow') === 0, `arrow=${count('arrow')}`)

  await cmd('setblock 2 100 0 minecraft:redstone_block')
  await cmd('setblock 2 101 0 minecraft:activator_rail')
  await cmd('fill 3 101 0 6 101 0 minecraft:rail')
  await sleep(800)
  const itemsBefore = itemCount()
  await cmd('data merge entity @e[type=minecart,limit=1,sort=nearest,distance=..6] {Motion:[0.08,0.0,0.0]}')
  const ejected = await waitFor(
    () => itemCount() > itemsBefore &&
      Object.values(bot.entities).find((e) => e.name === 'item' && e.position.distanceTo(bot.entity.position) < 8),
    4000, 'ejected item')
  check('驶过激活铁轨弹出物品', !!ejected, ejected ? `${ejected.displayName || ''} at ${ejected.position}` : '')
  await sleep(400)
  await cmd('tp @e[type=minecart,limit=1,sort=nearest,distance=..12] 2.5 101 0.5')
  await cmd('data merge entity @e[type=minecart,limit=1,sort=nearest,distance=..12] {Motion:[0.0,0.0,0.0]}')
  await sleep(800)
  // 刚弹出时服务端可能还在收尾，重开几次窗口读到稳定值
  let arrowsLeft = null
  for (let i = 0; i < 4; i++) {
    const w = await openWindow(ANY_CART, `dispenser window 2 (${i + 1})`, 2)
    if (!w) { await sleep(600); continue }
    arrowsLeft = contentsOf(w, 'arrow')
    bot.closeWindow(w)
    await sleep(500)
    if (arrowsLeft === 7) break
  }
  check('弹出的那件从库存扣掉了', arrowsLeft === 7, `left=${arrowsLeft}`)

  // 11) 交付被取消（需要 SmokeHooks 替身）：背包塞满 + 掉落全禁 -> 取下必须放弃并保持原样
  if (STUB_HOOKS) {
    if (!await freshCart()) return
    await give('stone')
    await sleep(900)
    await attach('stone')
    check('（前置）石头装到矿车上', count('stone') === 0, `stone=${count('stone')}`)
    const metaBefore = JSON.stringify(cartEntity(ANY_CART)?.metadata)

    await give('dirt', 2304) // 36 格全部塞满
    await sleep(1200)
    await cmd('smokehook on')
    await sleep(500)
    const cancelCart = cartEntity(ANY_CART)
    if (cancelCart) await sneakUse(cancelCart)
    else check('（前置）矿车还在', false, '找不到矿车')
    check('交付无路可走时矿车保持原样', JSON.stringify(cartEntity(ANY_CART)?.metadata) === metaBefore,
      'metadata ' + JSON.stringify(cartEntity(ANY_CART)?.metadata))
    check('交付无路可走时方块没凭空消失到背包', count('stone') === 0, `stone=${count('stone')}`)
    const strayItem = Object.values(bot.entities).find((e) => e.name === 'item' && e.position.distanceTo(bot.entity.position) < 8)
    check('交付被取消时不掉东西', itemCount() === 0 && !strayItem, strayItem ? `at ${strayItem.position}` : '')

    // 对照组：背包依旧塞满，但允许掉落 -> 方块应该被丢到地上并从矿车上消失
    await cmd('smokehook off')
    await sleep(400)
    const cartBefore = cartEntity(ANY_CART)
    if (cartBefore) await sneakUse(cartBefore)
    const dropped = await waitFor(
      () => Object.values(bot.entities).find((e) => e.name === 'item' && e.position.distanceTo(bot.entity.position) < 8),
      4000, 'dropped stone')
    check('允许掉落时，方块被丢到地上', !!dropped, dropped ? `at ${dropped.position}` : '')
    check('允许掉落时，矿车上不再有方块', JSON.stringify(cartEntity(ANY_CART)?.metadata) !== metaBefore, 'metadata 没变')
    check('允许掉落时，掉出去的不是背包里的别的物品', count('dirt') === 2304, `dirt=${count('dirt')}`)

    await cmd('kill @e[type=item]')
    await cmd(`clear ${USER}`)
    await sleep(600)
    await give('stone')
    await sleep(900)
    await attach('stone')
    await takeOff('stone')
    check('恢复后再取，石头回到背包', count('stone') === 1, `stone=${count('stone')}`)
  }

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
