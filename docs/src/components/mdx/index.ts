import Caption from './Caption.astro';
import CodeCompare from './CodeCompare.astro';
import Compare from './Compare.astro';
import Lead from './Lead.astro';
import LockLadder from './LockLadder.astro';
import LockTier from './LockTier.astro';
import Note from './Note.astro';
import Side from './Side.astro';
import Term from './Term.astro';
import TuningItem from './TuningItem.astro';
import TuningList from './TuningList.astro';
import WorkerScalingChart from '../charts/WorkerScalingChart.astro';
import DumbbellChart from '../charts/DumbbellChart.astro';

/** Components every docs page's MDX may use without importing them. */
export const mdxComponents = {
  Caption,
  CodeCompare,
  Compare,
  Lead,
  LockLadder,
  LockTier,
  Note,
  Side,
  Term,
  TuningItem,
  TuningList,
  WorkerScalingChart,
  DumbbellChart,
};
