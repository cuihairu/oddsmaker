/**
 * game-events.ts 源码直测(node:test):19 个事件方法 + 常量 + 工厂。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  GameEvents, createGameEvents,
  GameEventType, BattleMode, BattleResult, QuestType, ItemRarity, AdType, SocialAction, ErrorType,
} from '../src/game-events.ts';

function recorder() {
  const calls: Array<[string, Record<string, any>]> = [];
  const ge = createGameEvents((name, props) => { calls.push([name!, props!]); return 'eid'; });
  return { calls, ge };
}

test('常量对象完整且值合法', () => {
  assert.equal(Object.keys(GameEventType).length, 19);
  assert.equal(GameEventType.LEVEL_START, 'level_start');
  assert.equal(GameEventType.ERROR_NETWORK, 'error_network');
  assert.equal(Object.keys(BattleMode).length, 5);
  assert.equal(BattleMode.PVP, 'pvp');
  assert.equal(Object.keys(BattleResult).length, 4);
  assert.equal(BattleResult.WIN, 'win');
  assert.equal(Object.keys(QuestType).length, 5);
  assert.equal(QuestType.DAILY, 'daily');
  assert.equal(Object.keys(ItemRarity).length, 5);
  assert.equal(ItemRarity.LEGENDARY, 'legendary');
  assert.equal(Object.keys(AdType).length, 4);
  assert.equal(AdType.REWARDED, 'rewarded');
  assert.equal(Object.keys(SocialAction).length, 7);
  assert.equal(SocialAction.FRIEND_ACCEPT, 'friend_accept');
  assert.equal(Object.keys(ErrorType).length, 5);
  assert.equal(ErrorType.TIMEOUT, 'timeout');
});

test('19 个事件方法:事件名与 game_event_type 一致,透传 props', () => {
  const { calls, ge } = recorder();
  ge.levelStart({ level_id: '1', level_name: '第一关' });
  ge.levelComplete({ level_id: '1', level_score: 99 });
  ge.levelFail({ level_id: '1', fail: 1 });
  ge.battleStart({ battle_mode: BattleMode.PVP });
  ge.battleEnd({ battle_mode: BattleMode.PVE, battle_result: BattleResult.WIN, battle_kills: 3 });
  ge.questAccept({ quest_id: 'q1', quest_type: QuestType.MAIN });
  ge.questComplete({ quest_id: 'q1', quest_progress: 100 });
  ge.achievementUnlock({ achievement_id: 'a1', achievement_points: 10 });
  ge.itemGrant({ item_id: 'i1', item_rarity: ItemRarity.RARE, item_quantity: 2 });
  ge.itemConsume({ item_id: 'i1' });
  ge.currencySource({ currency_type: 'gold', currency_amount: 5 });
  ge.currencySink({ currency_type: 'gold', currency_amount: 2, currency_balance: 3 });
  ge.adWatch({ ad_type: AdType.INTERSTITIAL });
  ge.adReward({ ad_type: AdType.REWARDED, ad_reward_amount: 5 });
  ge.socialInvite({ social_action: SocialAction.INVITE });
  ge.socialAccept({ social_action: SocialAction.ACCEPT, social_target_user_id: 'u2' });
  ge.errorCrash({ error_type: ErrorType.CRASH });
  ge.errorException({ error_type: ErrorType.EXCEPTION });
  ge.errorNetwork({ error_type: ErrorType.NETWORK });

  assert.equal(calls.length, 19);
  for (const [name, props] of calls) {
    assert.equal(props.game_event_type, name, `${name} 的 game_event_type 应与事件名一致`);
  }
  assert.equal(calls[0][0], GameEventType.LEVEL_START);
  assert.equal(calls[0][1].level_id, '1');
  assert.equal(calls[4][1].battle_kills, 3);
  assert.equal(calls[16][0], GameEventType.ERROR_CRASH);
  assert.equal(calls[16][1].error_type, 'crash');
  // errorCrash 额外注入 error_fatal(其余 error 方法不注入)
  assert.equal(calls[17][1].error_fatal, undefined);
  assert.equal(calls[18][0], GameEventType.ERROR_NETWORK);
});

test('errorCrash 额外注入 error_fatal: true', () => {
  const { calls, ge } = recorder();
  ge.errorCrash({ error_type: 'crash', error_message: 'boom' });
  assert.equal(calls[0][1].error_fatal, true);
});

test('createGameEvents 与 new GameEvents 等价,track 返回值透传', () => {
  const calls: Array<[string, Record<string, any> | undefined]> = [];
  const ge = new GameEvents((name, props) => { calls.push([name!, props]); return 'x'; });
  assert.equal(ge.adWatch({ ad_duration_ms: 3000 }), 'x');
  assert.equal(calls[0][0], 'ad_watch');
  assert.equal(calls[0][1]!.game_event_type, 'ad_watch');
  assert.equal(calls[0][1]!.ad_duration_ms, 3000);
  assert.ok(createGameEvents(calls[0][1] ? (n, p) => n + p : () => '') instanceof GameEvents);
});
