package com.homedatacenter.app.ui.automations

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.AutomationRule
import com.homedatacenter.app.data.model.CreateAutomationRuleRequest
import com.homedatacenter.app.data.model.NumberOp
import com.homedatacenter.app.data.model.RuleAction
import com.homedatacenter.app.data.model.RuleCondition
import com.homedatacenter.app.data.model.RuleThrottle
import com.homedatacenter.app.data.model.UpdateAutomationRuleRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AutomationsActivity — Full Jetpack Compose declarative architecture (v1.13.0, 四.2).
 * Features a visual pipeline card design:
 *   [触发源 (Trigger)] ➔ [过滤规则 (Filter)] ➔ [联动动作 (Action)]
 * with execution heat indicators, metrics banner, and live rule testing.
 */
class AutomationsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HomeCenterApp).container

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF64B5F6),
                    secondary = Color(0xFF81C784),
                    background = Color(0xFF121418),
                    surface = Color(0xFF1E222A),
                    onPrimary = Color.Black,
                    onSurface = Color(0xFFE8EAED),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1976D2),
                    secondary = Color(0xFF388E3C),
                    background = Color(0xFFF7F9FC),
                    surface = Color(0xFFFFFFFF),
                    onPrimary = Color.White,
                    onSurface = Color(0xFF1A1C1E),
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                AutomationsScreen(
                    container = container,
                    onBack = { finish() }
                )
            }
        }
    }
}

private val TRIGGER_OPTIONS = listOf(
    "detection" to "目标检测 (detection)",
    "camera.fall_detected" to "摔倒高危告警 (fall_detected)",
    "camera.person_recognized" to "家人面部识别 (person_recognized)",
    "alert" to "安全告警 (alert)",
    "camera.offline" to "摄像头离线 (camera.offline)",
    "camera.online" to "摄像头上线 (camera.online)",
)

private val ACTION_OPTIONS = listOf(
    "notify" to "移动端富媒体推送 (notify)",
    "mqtt" to "MQTT 智能家居联动 (mqtt)",
    "webhook" to "Webhook 自定义接口 (webhook)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationsScreen(
    container: com.homedatacenter.app.di.AppContainer,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<AutomationRule>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var sheetRuleToEdit by remember { mutableStateOf<AutomationRule?>(null) }
    var isSheetOpen by remember { mutableStateOf(false) }

    var testDialogRule by remember { mutableStateOf<AutomationRule?>(null) }
    var testResultText by remember { mutableStateOf<String?>(null) }

    var deleteConfirmRule by remember { mutableStateOf<AutomationRule?>(null) }

    val loadRules: () -> Unit = {
        val token = container.prefsManager.token
        if (!token.isNullOrEmpty()) {
            coroutineScope.launch {
                isLoading = true
                try {
                    val list = withContext(Dispatchers.IO) {
                        container.getRepository().listAutomationRules(token)
                    }
                    rules = list
                } catch (e: Exception) {
                    Toast.makeText(context, "获取规则失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRules()
    }

    val toggleRule: (AutomationRule, Boolean) -> Unit = { rule, newEnabled ->
        val token = container.prefsManager.token
        if (!token.isNullOrEmpty()) {
            coroutineScope.launch {
                try {
                    val req = UpdateAutomationRuleRequest(
                        name = rule.name,
                        trigger = rule.trigger,
                        condition = rule.condition,
                        action = rule.action,
                        throttle = rule.throttle,
                        enabled = newEnabled,
                    )
                    val updated = withContext(Dispatchers.IO) {
                        container.getRepository().updateAutomationRule(token, rule.id, req)
                    }
                    rules = rules.map { if (it.id == rule.id) updated else it }
                    Toast.makeText(context, if (newEnabled) "规则已启用" else "规则已停用", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    loadRules()
                }
            }
        }
    }

    val testRule: (AutomationRule) -> Unit = { rule ->
        val token = container.prefsManager.token
        if (!token.isNullOrEmpty()) {
            coroutineScope.launch {
                try {
                    Toast.makeText(context, "正在测试触发规则...", Toast.LENGTH_SHORT).show()
                    val res = withContext(Dispatchers.IO) {
                        container.getRepository().testAutomationRule(token, rule.id)
                    }
                    testDialogRule = rule
                    testResultText = "规则「${res.name}」已成功触发！\n执行动作: ${res.action}\n状态: 测试运行正常"
                    loadRules()
                } catch (e: Exception) {
                    Toast.makeText(context, "测试失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val deleteRule: (AutomationRule) -> Unit = { rule ->
        val token = container.prefsManager.token
        if (!token.isNullOrEmpty()) {
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        container.getRepository().deleteAutomationRule(token, rule.id)
                    }
                    rules = rules.filter { it.id != rule.id }
                    Toast.makeText(context, "规则已删除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "自动化联动规则",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "端云协同智能防护与告警流转",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = loadRules) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    sheetRuleToEdit = null
                    isSheetOpen = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "新建规则",
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Metrics Banner
            val activeCount = rules.count { it.enabled }
            val totalCount = rules.size
            val totalFires = rules.sumOf { it.fireCount }

            AutomationsMetricsCard(
                activeCount = activeCount,
                totalCount = totalCount,
                totalFires = totalFires,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (rules.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bolt),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无自动化联动规则",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "创建规则以实现多设备自动联动与智能防护",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                sheetRuleToEdit = null
                                isSheetOpen = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("新建首条联动规则")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(rules, key = { it.id }) { rule ->
                        AutomationPipelineCard(
                            rule = rule,
                            onToggle = { newEnabled -> toggleRule(rule, newEnabled) },
                            onTest = { testRule(rule) },
                            onEdit = {
                                sheetRuleToEdit = rule
                                isSheetOpen = true
                            },
                            onDelete = { deleteConfirmRule = rule }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // Add / Edit Rule ModalBottomSheet
    if (isSheetOpen) {
        RuleEditSheet(
            rule = sheetRuleToEdit,
            onDismiss = { isSheetOpen = false },
            onSave = { name, trigger, condition, action, throttle ->
                val token = container.prefsManager.token ?: return@RuleEditSheet
                coroutineScope.launch {
                    try {
                        if (sheetRuleToEdit == null) {
                            val req = CreateAutomationRuleRequest(
                                name = name,
                                trigger = trigger,
                                condition = condition,
                                action = action,
                                throttle = throttle,
                                enabled = true
                            )
                            withContext(Dispatchers.IO) {
                                container.getRepository().createAutomationRule(token, req)
                            }
                            Toast.makeText(context, "规则创建成功", Toast.LENGTH_SHORT).show()
                        } else {
                            val req = UpdateAutomationRuleRequest(
                                name = name,
                                trigger = trigger,
                                condition = condition,
                                action = action,
                                throttle = throttle,
                                enabled = sheetRuleToEdit?.enabled ?: true
                            )
                            withContext(Dispatchers.IO) {
                                container.getRepository().updateAutomationRule(token, sheetRuleToEdit!!.id, req)
                            }
                            Toast.makeText(context, "规则已更新", Toast.LENGTH_SHORT).show()
                        }
                        isSheetOpen = false
                        loadRules()
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Test Result Dialog
    if (testDialogRule != null && testResultText != null) {
        AlertDialog(
            onDismissRequest = {
                testDialogRule = null
                testResultText = null
            },
            title = { Text("测试触发成功", fontWeight = FontWeight.Bold) },
            text = { Text(testResultText.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    testDialogRule = null
                    testResultText = null
                }) {
                    Text("完成")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (deleteConfirmRule != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmRule = null },
            title = { Text("确认删除规则") },
            text = { Text("确定要删除自动化规则「${deleteConfirmRule?.name}」吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteConfirmRule
                        deleteConfirmRule = null
                        if (target != null) deleteRule(target)
                    }
                ) {
                    Text("确认删除", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmRule = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AutomationsMetricsCard(
    activeCount: Int,
    totalCount: Int,
    totalFires: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$activeCount / $totalCount",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "活跃规则 / 总数",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$totalFires",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "累计自动触发",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationPipelineCard(
    rule: AutomationRule,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Name + Heat Tag + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = rule.name.ifBlank { "未命名规则" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    HeatBadge(fireCount = rule.fireCount)
                }

                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pipeline Visualizer: [触发源] ➔ [过滤规则] ➔ [联动动作]
            Text(
                text = "联动链路",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Node 1: Trigger
                val triggerLabel = when (rule.trigger) {
                    "detection" -> "目标检测"
                    "camera.fall_detected" -> "摔倒识别"
                    "camera.person_recognized" -> "面部识别"
                    "alert" -> "安全告警"
                    "camera.offline" -> "设备离线"
                    "camera.online" -> "设备上线"
                    else -> rule.trigger
                }
                PipelineNodeChip(text = triggerLabel, bgColor = Color(0xFFE3F2FD), textColor = Color(0xFF1565C0))

                Text("➔", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))

                // Node 2: Filter / Condition
                val filterLabel = buildString {
                    val cond = rule.condition
                    if (cond?.label != null) append(cond.label)
                    if (cond?.confidence != null) {
                        if (isNotEmpty()) append(" ")
                        append("≥${(cond.confidence!! * 100).toInt()}%")
                    }
                    if (isEmpty()) append("全部通过")
                }
                PipelineNodeChip(text = filterLabel, bgColor = Color(0xFFFFF3E0), textColor = Color(0xFFE65100))

                Text("➔", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))

                // Node 3: Action
                val actionLabel = when (rule.action?.type) {
                    "notify" -> "富媒体推送"
                    "mqtt" -> "MQTT 联动"
                    "webhook" -> "Webhook"
                    else -> rule.action?.type ?: "notify"
                }
                PipelineNodeChip(text = actionLabel, bgColor = Color(0xFFE8F5E9), textColor = Color(0xFF2E7D32))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer info & actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val cooldown = rule.throttle?.cooldownSeconds ?: 0
                Text(
                    text = "触发 ${rule.fireCount} 次 · 冷却 ${cooldown}s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onTest,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("测试", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onEdit,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("编辑", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onDelete,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("删除", fontSize = 12.sp, color = Color(0xFFE53935))
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineNodeChip(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HeatBadge(fireCount: Long) {
    val (text, color, bg) = when {
        fireCount >= 20 -> Triple("高频", Color(0xFFD32F2F), Color(0xFFFFEBEE))
        fireCount > 0 -> Triple("正常", Color(0xFF1976D2), Color(0xFFE3F2FD))
        else -> Triple("未触发", Color(0xFF757575), Color(0xFFEEEEEE))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditSheet(
    rule: AutomationRule?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        trigger: String,
        condition: RuleCondition?,
        action: RuleAction,
        throttle: RuleThrottle?
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(rule?.name.orEmpty()) }
    var trigger by remember { mutableStateOf(rule?.trigger ?: "detection") }
    var labelFilter by remember { mutableStateOf(rule?.condition?.label.orEmpty()) }
    var confidenceStr by remember {
        mutableStateOf(rule?.condition?.confidence?.let { "${(it * 100).toInt()}" } ?: "70")
    }

    var actionType by remember { mutableStateOf(rule?.action?.type ?: "notify") }
    var actionTitle by remember { mutableStateOf(rule?.action?.title.orEmpty()) }
    var actionBody by remember { mutableStateOf(rule?.action?.body.orEmpty()) }
    var cooldownSec by remember { mutableStateOf("${rule?.throttle?.cooldownSeconds ?: 30}") }

    var triggerExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (rule == null) "新建自动化规则" else "编辑自动化规则",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则名称") },
                placeholder = { Text("如：客厅人形告警即时推送") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Trigger Dropdown
            ExposedDropdownMenuBox(
                expanded = triggerExpanded,
                onExpandedChange = { triggerExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = TRIGGER_OPTIONS.find { it.first == trigger }?.second ?: trigger,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("触发事件 (Trigger)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = triggerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = triggerExpanded,
                    onDismissRequest = { triggerExpanded = false }
                ) {
                    TRIGGER_OPTIONS.forEach { (key, display) ->
                        DropdownMenuItem(
                            text = { Text(display) },
                            onClick = {
                                trigger = key
                                triggerExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Condition Filters (for detection)
            if (trigger == "detection") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = labelFilter,
                        onValueChange = { labelFilter = it },
                        label = { Text("目标标签 (如 person/car)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = confidenceStr,
                        onValueChange = { confidenceStr = it },
                        label = { Text("置信度 (%)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action Dropdown
            ExposedDropdownMenuBox(
                expanded = actionExpanded,
                onExpandedChange = { actionExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = ACTION_OPTIONS.find { it.first == actionType }?.second ?: actionType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("联动动作 (Action)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = actionExpanded,
                    onDismissRequest = { actionExpanded = false }
                ) {
                    ACTION_OPTIONS.forEach { (key, display) ->
                        DropdownMenuItem(
                            text = { Text(display) },
                            onClick = {
                                actionType = key
                                actionExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Action Details
            OutlinedTextField(
                value = actionTitle,
                onValueChange = { actionTitle = it },
                label = { Text("动作标题") },
                placeholder = { Text("如：客厅发现异常移动！") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = actionBody,
                onValueChange = { actionBody = it },
                label = { Text("通知内容 / 载荷") },
                placeholder = { Text("如：检测到人员进入监控区域") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = cooldownSec,
                onValueChange = { cooldownSec = it },
                label = { Text("冷却防抖间隔 (秒)") },
                placeholder = { Text("30") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (name.isBlank()) {
                        return@Button
                    }
                    val cond = if (trigger == "detection" && (labelFilter.isNotBlank() || confidenceStr.isNotBlank())) {
                        val conf = confidenceStr.toDoubleOrNull()?.let { it / 100.0 } ?: 0.7
                        RuleCondition(
                            payloadEq = if (labelFilter.isNotBlank()) mapOf("label" to labelFilter.trim()) else null,
                            threshold = mapOf("confidence" to NumberOp(">=", conf))
                        )
                    } else null

                    val act = RuleAction(
                        type = actionType,
                        title = actionTitle.ifBlank { name },
                        body = actionBody.ifBlank { "触发联动动作" }
                    )

                    val throttle = RuleThrottle(
                        cooldownS = cooldownSec.toIntOrNull() ?: 30,
                        dedup = true
                    )

                    onSave(name, trigger, cond, act, throttle)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存规则", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
