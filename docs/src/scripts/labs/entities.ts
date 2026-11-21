import { byId, selectButton } from './common';

type StepKind = 'v' | 'n' | 'ok';
type Step = [kind: StepKind, tag: string, text: string];
type Scenario = { vanillaCost: string; vanadiumCost: string; vanilla: Step[]; vanadium: Step[] };

const scenarios: Record<string, Scenario> = {
  push: {
    vanillaCost: 'Serial, 2 Vec3 allocations',
    vanadiumCost: 'Parallel, 0 allocations',
    vanilla: [
      ['v', 'Step 1', 'Server ticks Zombie A, reads B bounding box, allocates new Vec3, mutates B velocity.'],
      ['v', 'Step 2', 'Server finishes A, then ticks Zombie B, allocates new Vec3, mutates A velocity.'],
    ],
    vanadium: [
      ['n', 'Core 1', 'Owns Zombie A. Adds (dx, 0, dz) into B.impulseSlot (3 primitives, no Vec3).'],
      ['n', 'Core 2', 'Owns Zombie B. Adds (dx, 0, dz) into A.impulseSlot concurrently with zero blocking.'],
      ['ok', 'Drain', 'Each entity applies its own ImpulseSlot at tick start or during the post-wave drain.'],
    ],
  },
  hurt: {
    vanillaCost: 'Serial',
    vanadiumCost: 'Parallel, deadlock-free',
    vanilla: [
      ['v', 'Step 1', 'Server ticks Iron Golem and calls Zombie.hurt() directly on the same thread.'],
      ['v', 'Step 2', 'Server ticks Zombie and calls IronGolem.hurt() after Golem completes.'],
    ],
    vanadium: [
      ['n', 'Core 1', 'Golem releases its own OwnerLock first, acquires Zombie lock, applies hurt().'],
      ['n', 'Core 2', 'Zombie releases its own OwnerLock first, acquires Golem lock, applies hurt().'],
      ['ok', 'Result', 'Releasing own lock before entering a foreign scope eliminates the A/B deadlock cycle.'],
    ],
  },
  merge: {
    vanillaCost: 'Serial',
    vanadiumCost: 'Parallel, duplicate-free',
    vanilla: [
      ['v', 'Step 1', 'Stack #10 finds neighbor Stack #11, adds counts, marks #11 discarded.'],
      ['v', 'Step 2', 'Stack #11 is already discarded and skipped.'],
    ],
    vanadium: [
      ['n', 'Core 1+2', 'Both stacks enter Interactions.enterPair(#10, #11), locking lower ID #10 then #11.'],
      ['ok', 'Winner', 'Core 1 acquires both locks, merges counts, discards #11, releases both locks.'],
      ['ok', 'Recheck', 'Core 2 enters, rechecks isAlive(), sees #11 is already dead, and exits cleanly.'],
    ],
  },
};

function steps(list: Step[]): HTMLDivElement[] {
  return list.map(([kind, tag, text]) => {
    const step = document.createElement('div');
    step.className = `swim-step ${kind}`;
    const tagEl = document.createElement('span');
    tagEl.className = 'swim-tag';
    tagEl.textContent = tag;
    const textEl = document.createElement('span');
    textEl.textContent = text;
    step.append(tagEl, textEl);
    return step;
  });
}

function show(key: string): void {
  const scenario = scenarios[key];
  if (!scenario) throw new Error(`Unknown entity scenario ${key}`);
  byId('entVanillaCost').textContent = scenario.vanillaCost;
  byId('entVanadiumCost').textContent = scenario.vanadiumCost;
  byId('entVanillaLog').replaceChildren(...steps(scenario.vanilla));
  byId('entVanadiumLog').replaceChildren(...steps(scenario.vanadium));
}

const buttons = [...document.querySelectorAll<HTMLButtonElement>('[data-entity-case]')];
for (const button of buttons) {
  button.addEventListener('click', () => {
    selectButton(buttons, button);
    show(button.dataset.entityCase ?? 'push');
  });
}
show('push');
