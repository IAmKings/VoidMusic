package com.electrodig.voidmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.ui.theme.Amber
import com.electrodig.voidmusic.ui.theme.BackgroundDark
import com.electrodig.voidmusic.ui.theme.Cyan
import com.electrodig.voidmusic.ui.theme.Lime
import kotlinx.coroutines.launch

private data class OnboardPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tint: Color
)

private val pages = listOf(
    OnboardPage(
        icon = Icons.Default.TouchApp,
        title = "把日常物件变成鼓机",
        body = "在桌面上摆放彩色贴纸、玩具或纸面色块，App 会把它们识别成可演奏的鼓区。",
        tint = Amber
    ),
    OnboardPage(
        icon = Icons.Default.CameraAlt,
        title = "准备桌面与摄像头",
        body = "将手机俯拍桌面（约 30°~60°），需要授权摄像头权限来识别物件与追踪手部。",
        tint = Cyan
    ),
    OnboardPage(
        icon = Icons.Default.GridView,
        title = "两种玩法",
        body = "「实时击打」用手指触碰物件发声；「步进序列」把纸面变成 4×16 节奏循环。",
        tint = Lime
    )
)

/**
 * First-launch introduction (PRD F1.1). 3 swipeable pages then a CTA into the
 * permission flow.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(0f to BackgroundDark, 1f to Color(0xFF262640))
            )
            .padding(24.dp)
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val p = pages[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(p.tint.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(p.icon, contentDescription = null, tint = p.tint, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.size(28.dp))
                Text(
                    p.title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    p.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        Modifier
                            .size(if (active) 10.dp else 8.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape
                            )
                    )
                }
            }
            Spacer(Modifier.size(20.dp))
            if (pagerState.currentPage == pages.lastIndex) {
                Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                    Text("开始体验")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onComplete) { Text("跳过") }
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) { Text("下一步") }
                }
            }
        }
    }
}
