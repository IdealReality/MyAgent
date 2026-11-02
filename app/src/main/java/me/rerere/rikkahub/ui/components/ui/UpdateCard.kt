package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.pages.chat.ChatVM

/**
 * 【MyAgent 提示】应用层的上游版本获取已停用：ChatVM.updateState 恒为 Loading，
 * 本组件不再渲染任何内容（原卡片/下载逻辑见 git 历史，停用决策与恢复方法
 * 记录在 app/build.gradle.kts 的 buildTypes 注释里）。
 * 保留函数签名仅为兼容 ChatDrawer 的调用面；手动查上游版本用 agent 的
 * check_app_updates 工具（ReliabilityTools / GitHubReleaseChecker）。
 */
@Composable
fun UpdateCard(vm: ChatVM) {
}
