import type { SlideCopy } from "./types";

export const zhCN: SlideCopy = {
  locale: "zh-CN",
  dir: "ltr",

  slide1: {
    label: "自由上网",
    headline: ["无边界", "上网", "畅游"],
  },

  slide2: {
    eyebrow: "无需 root",
    headline: ["一键开启", "无需 root"],
    cards: [
      { title: "支持任何 Android", desc: "无需解锁,不用魔改" },
      { title: "一键连接", desc: "瞬间生效" },
      { title: "本地 VPN 或代理", desc: "流量不离开你的设备" },
    ],
    bottomBadge: "任何设备都能用",
  },

  slide3: {
    label: "隐私与安全",
    headline: ["你的隐私", "你做主"],
    pills: ["加密 DNS", "拦截 WebRTC", "生物锁"],
  },

  slide4: {
    label: "进阶控制",
    headline: ["每个数据包", "都能微调"],
    sectionEncryptedDns: "加密 DNS",
    sectionDpiBypass: "DPI 绕过",
    sectionModes: "连接模式",
    modeVpn: "本地 VPN",
    modeProxy: "本地代理",
    footer: "新手有预设,玩家有全权。",
  },

  slide5: {
    label: "内置诊断",
    headline: ["看清网络", "究竟", "怎么了"],
  },

  slide6: {
    headline: ["还有更多", "等你发现"],
    features: [
      "分网络策略",
      "兼容 AdGuard",
      "会话遥测",
      "WebRTC 防护",
      "生物锁",
      "连接历史",
      "支持热点共享",
      "数据导出",
    ],
    comingSoonLabel: "即将推出",
    comingSoon: ["主机包", "社区统计"],
  },

  featureGraphic: {
    tagline: "无边界上网畅游",
  },
};
