// Package xtunnel 实现 x-tunnel 多通道 WebSocket 隧道协议后端：
// 通道选择竞争、Hot Channel Pair、Fast Retry、背压控制、UDP associate、
// HTTP 代理与中转节点健康评分/负载均衡。
// 后端通过 xtunnel.Backend 满足 xclient.ProxyBackend 接口（结构化实现）。
package xtunnel
