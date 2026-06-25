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
      { title: "本地 DPI 绕过", desc: "在本机运行,无需服务器" },
      { title: "或用自有中继", desc: "VLESS、Hysteria2、Tor 等" },
      { title: "内置加密 DNS", desc: "DoH、DoT、DNSCrypt" },
    ],
    bottomBadge: "任何设备都能用",
  },

  slide3: {
    label: "远程中继",
    headline: ["本地绕过", "或用中继"],
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
      "分流隧道",
      "路由规则",
      "DPI 检测",
      "数据透明",
      "7 款应用图标",
      "备份与恢复",
      "分网络策略",
      "生物锁",
    ],
    comingSoonLabel: "即将推出",
    comingSoon: ["主机包", "社区统计"],
  },

  featureGraphic: {
    tagline: "无边界上网畅游",
  },
};
