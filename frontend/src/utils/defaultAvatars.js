// 默认头像库：混合「流行经典」与「当下最新」风格（来源 DiceBear 公开 API）。
// 以 URL 形式直接写入 user.avatar 字段即可；若需离线使用，可把图片下载到 /uploads 后改为本地路径。
const BASE = 'https://api.dicebear.com/9.x'

// 每项：style= DiceBear 风格，seed= 决定图形的随机种子（不同 seed 头像不同）
const RAW = [
  // —— 流行经典 ——
  { style: 'bottts',     seed: 'kb-robot',   label: '机器人' },
  { style: 'avataaars',  seed: 'kb-ava',     label: '卡通脸' },
  { style: 'fun-emoji',  seed: 'kb-fun',     label: '表情' },
  { style: 'open-peeps', seed: 'kb-peep',    label: '简笔人' },
  { style: 'lorelei',    seed: 'kb-loli',    label: '插画女' },
  { style: 'notionists', seed: 'kb-note',    label: '线条人' },
  { style: 'micah',      seed: 'kb-micah',   label: '极简男' },
  { style: 'thumbs',     seed: 'kb-thumb',   label: '大拇指' },
  // —— 当下最新 ——
  { style: 'miniavs',    seed: 'kb-mini',    label: '迷你风' },
  { style: 'big-smile',  seed: 'kb-smile',   label: '笑脸' },
  { style: 'personas',   seed: 'kb-persona', label: '角色' },
  { style: 'pixel-art',  seed: 'kb-pixel',   label: '像素' },
  { style: 'adventurer', seed: 'kb-adv',     label: '冒险者' },
  { style: 'big-ears',   seed: 'kb-ear',     label: '大耳' },
  { style: 'glass',      seed: 'kb-glass',   label: '玻璃' },
  { style: 'rings',      seed: 'kb-ring',    label: '几何' },
]

// 本地默认头像：从 DiceBear 下载到 assets/avatars，离线可用、不依赖外网（多种风格）
import dl1 from '../assets/avatars/dl1.svg'
import dl2 from '../assets/avatars/dl2.svg'
import dl3 from '../assets/avatars/dl3.svg'
import dl4 from '../assets/avatars/dl4.svg'
import dl5 from '../assets/avatars/dl5.svg'
import dl8 from '../assets/avatars/dl8.svg'
import dl9 from '../assets/avatars/dl9.svg'
import dl10 from '../assets/avatars/dl10.svg'
import dl11 from '../assets/avatars/dl11.svg'
import dl12 from '../assets/avatars/dl12.svg'

const LOCAL_AVATARS = [
  { url: dl1, label: '机器人' },
  { url: dl2, label: '卡通猫' },
  { url: dl3, label: '插画女' },
  { url: dl4, label: '极简男' },
  { url: dl5, label: '开心表情' },
  { url: dl8, label: '几何形' },
  { url: dl9, label: '像素标识' },
  { url: dl10, label: '几何脸' },
  { url: dl11, label: '图标' },
  { url: dl12, label: '手绘涂鸦' },
]

export const DEFAULT_AVATARS = [
  ...RAW.map(a => ({
    ...a,
    url: `${BASE}/${a.style}/svg?seed=${encodeURIComponent(a.seed)}`,
  })),
  ...LOCAL_AVATARS,
]
