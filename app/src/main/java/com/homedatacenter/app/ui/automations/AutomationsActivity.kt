package com.homedatacenter.app.ui.automations

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.AutomationRule
import com.homedatacenter.app.data.model.CreateAutomationRuleRequest
import com.homedatacenter.app.data.model.NumberOp
import com.homedatacenter.app.data.model.RuleAction
import com.homedatacenter.app.data.model.RuleCondition
import com.homedatacenter.app.data.model.RuleThrottle
import com.homedatacenter.app.data.model.UpdateAutomationRuleRequest
import com.homedatacenter.app.databinding.ActivityAutomationsBinding
import com.homedatacenter.app.databinding.BottomSheetEditRuleBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

class AutomationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutomationsBinding
    private lateinit var container: AppContainer
    private lateinit var adapter: AutomationRuleAdapter

    private val triggerKeys = listOf("detection", "alert", "camera.offline", "camera.online")
    private val triggerLabels = listOf("目标检测 (detection)", "安全告警 (alert)", "摄像头离线 (camera.offline)", "摄像头上线 (camera.online)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { loadData() }
        binding.fabAdd.setOnClickListener { showEditRuleBottomSheet(null) }
        binding.btnEmptyAdd.setOnClickListener { showEditRuleBottomSheet(null) }

        adapter = AutomationRuleAdapter(
            onToggle = { rule, isChecked -> toggleRule(rule, isChecked) },
            onTest = { rule -> testRule(rule) },
            onEdit = { rule -> showEditRuleBottomSheet(rule) },
            onDelete = { rule -> confirmDeleteRule(rule) },
        )
        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = adapter

        loadData()
    }

    private fun loadData() {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            try {
                val rules = container.getRepository().listAutomationRules(token)
                adapter.submitList(rules)

                val activeCount = rules.count { it.enabled }
                val totalCount = rules.size
                binding.tvMetricsRules.text = getString(R.string.automations_metrics_rules, activeCount, totalCount)

                val totalFires = rules.sumOf { it.fireCount }
                binding.tvMetricsTriggers.text = getString(R.string.automations_metrics_triggers, totalFires)

                val isEmpty = rules.isEmpty()
                binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvRules.visibility = if (isEmpty) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@AutomationsActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun toggleRule(rule: AutomationRule, isChecked: Boolean) {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val req = UpdateAutomationRuleRequest(
                    name = rule.name,
                    trigger = rule.trigger,
                    condition = rule.condition,
                    action = rule.action,
                    throttle = rule.throttle,
                    enabled = isChecked,
                )
                val updated = container.getRepository().updateAutomationRule(token, rule.id, req)
                val current = adapter.currentList.map { if (it.id == rule.id) updated else it }
                adapter.submitList(current)

                val activeCount = current.count { it.enabled }
                binding.tvMetricsRules.text = getString(R.string.automations_metrics_rules, activeCount, current.size)
                Toast.makeText(this@AutomationsActivity, getString(R.string.automations_toggle_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AutomationsActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                // Revert switch on failure by reloading
                loadData()
            }
        }
    }

    private fun testRule(rule: AutomationRule) {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@AutomationsActivity, "正在测试规则...", Toast.LENGTH_SHORT).show()
                val res = container.getRepository().testAutomationRule(token, rule.id)
                AlertDialog.Builder(this@AutomationsActivity)
                    .setTitle("测试触发成功")
                    .setMessage("规则「${res.name}」已手动触发！\n执行动作: ${res.action}\n状态: 测试通过")
                    .setPositiveButton(R.string.action_done, null)
                    .show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(this@AutomationsActivity, getString(R.string.automations_test_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteRule(rule: AutomationRule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(getString(R.string.automations_delete_confirm, rule.name))
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                deleteRule(rule)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deleteRule(rule: AutomationRule) {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.getRepository().deleteAutomationRule(token, rule.id)
                Toast.makeText(this@AutomationsActivity, "规则已删除", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(this@AutomationsActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditRuleBottomSheet(ruleToEdit: AutomationRule?) {
        val sheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetEditRuleBinding.inflate(layoutInflater)
        sheetDialog.setContentView(sheetBinding.root)

        // Spinner setup
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, triggerLabels)
        sheetBinding.spinnerTrigger.adapter = spinnerAdapter

        // Switch trigger event visibility for detection filters
        sheetBinding.spinnerTrigger.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isDetection = triggerKeys.getOrNull(position) == "detection"
                sheetBinding.layoutDetectionFilter.visibility = if (isDetection) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Action RadioGroup listener
        sheetBinding.rgActionType.setOnCheckedChangeListener { _, checkedId ->
            sheetBinding.layoutActionNotify.visibility = if (checkedId == R.id.rbActionNotify) View.VISIBLE else View.GONE
            sheetBinding.layoutActionMqtt.visibility = if (checkedId == R.id.rbActionMqtt) View.VISIBLE else View.GONE
            sheetBinding.layoutActionWebhook.visibility = if (checkedId == R.id.rbActionWebhook) View.VISIBLE else View.GONE
        }

        // Preset templates
        sheetBinding.chipTplNightIntrusion.setOnClickListener {
            sheetBinding.etRuleName.setText("夜间人形入侵告警")
            sheetBinding.spinnerTrigger.setSelection(triggerKeys.indexOf("detection").coerceAtLeast(0))
            sheetBinding.etTimeStart.setText("22:00")
            sheetBinding.etTimeEnd.setText("06:00")
            sheetBinding.etLabel.setText("person")
            sheetBinding.etConfidence.setText("0.80")
            sheetBinding.rbActionNotify.isChecked = true
            sheetBinding.etNotifyTitle.setText("夜间人形入侵告警")
            sheetBinding.etNotifyMessage.setText("监控摄像头检测到夜间有人走动，请留意！")
            sheetBinding.etCooldown.setText("60")
        }

        sheetBinding.chipTplCamOffline.setOnClickListener {
            sheetBinding.etRuleName.setText("摄像头离线告警")
            sheetBinding.spinnerTrigger.setSelection(triggerKeys.indexOf("camera.offline").coerceAtLeast(0))
            sheetBinding.etTimeStart.setText("")
            sheetBinding.etTimeEnd.setText("")
            sheetBinding.rbActionNotify.isChecked = true
            sheetBinding.etNotifyTitle.setText("摄像头离线告警")
            sheetBinding.etNotifyMessage.setText("有摄像头断开连接，请检查局域网供电和网线")
            sheetBinding.etCooldown.setText("300")
        }

        sheetBinding.chipTplMqttLight.setOnClickListener {
            sheetBinding.etRuleName.setText("人来亮灯联动")
            sheetBinding.spinnerTrigger.setSelection(triggerKeys.indexOf("detection").coerceAtLeast(0))
            sheetBinding.etTimeStart.setText("18:30")
            sheetBinding.etTimeEnd.setText("06:00")
            sheetBinding.etLabel.setText("person")
            sheetBinding.etConfidence.setText("0.75")
            sheetBinding.rbActionMqtt.isChecked = true
            sheetBinding.etMqttTopic.setText("home/light/porch/set")
            sheetBinding.etMqttPayload.setText("{\"state\":\"ON\"}")
            sheetBinding.etCooldown.setText("30")
        }

        sheetBinding.chipTplWebhook.setOnClickListener {
            sheetBinding.etRuleName.setText("外部报警 Webhook")
            sheetBinding.spinnerTrigger.setSelection(triggerKeys.indexOf("alert").coerceAtLeast(0))
            sheetBinding.etTimeStart.setText("")
            sheetBinding.etTimeEnd.setText("")
            sheetBinding.rbActionWebhook.isChecked = true
            sheetBinding.etWebhookUrl.setText("https://api.pushdeer.com/message/push")
            sheetBinding.etCooldown.setText("60")
        }

        // If editing existing rule, populate fields
        if (ruleToEdit != null) {
            sheetBinding.tvSheetTitle.text = getString(R.string.automations_edit)
            sheetBinding.scrollTemplates.visibility = View.GONE
            sheetBinding.tvTemplateHeader.visibility = View.GONE
            sheetBinding.switchSheetEnabled.isChecked = ruleToEdit.enabled
            sheetBinding.etRuleName.setText(ruleToEdit.name)

            val trigIdx = triggerKeys.indexOf(ruleToEdit.trigger).takeIf { it >= 0 } ?: 0
            sheetBinding.spinnerTrigger.setSelection(trigIdx)

            ruleToEdit.condition?.let { cond ->
                sheetBinding.etTimeStart.setText(cond.startTime.orEmpty())
                sheetBinding.etTimeEnd.setText(cond.endTime.orEmpty())
                sheetBinding.etLabel.setText(cond.label.orEmpty().ifBlank { "person" })
                sheetBinding.etConfidence.setText(cond.confidence?.toString() ?: "0.80")
            }

            ruleToEdit.action?.let { action ->
                when (action.type) {
                    "notify" -> {
                        sheetBinding.rbActionNotify.isChecked = true
                        sheetBinding.etNotifyTitle.setText(action.title.orEmpty())
                        sheetBinding.etNotifyMessage.setText(action.body.orEmpty())
                    }
                    "mqtt" -> {
                        sheetBinding.rbActionMqtt.isChecked = true
                        sheetBinding.etMqttTopic.setText(action.topic.orEmpty())
                        sheetBinding.etMqttPayload.setText(action.payload.orEmpty())
                    }
                    "webhook" -> {
                        sheetBinding.rbActionWebhook.isChecked = true
                        sheetBinding.etWebhookUrl.setText(action.url.orEmpty())
                    }
                }
            }

            sheetBinding.etCooldown.setText(ruleToEdit.throttle?.cooldownSeconds?.toString() ?: "60")
        }

        sheetBinding.btnCancel.setOnClickListener { sheetDialog.dismiss() }

        sheetBinding.btnSave.setOnClickListener {
            val name = sheetBinding.etRuleName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "请输入规则名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val triggerPos = sheetBinding.spinnerTrigger.selectedItemPosition
            val trigger = triggerKeys.getOrElse(triggerPos) { "detection" }

            val timeStart = sheetBinding.etTimeStart.text?.toString()?.trim().takeIf { !it.isNullOrBlank() }
            val timeEnd = sheetBinding.etTimeEnd.text?.toString()?.trim().takeIf { !it.isNullOrBlank() }
            val label = sheetBinding.etLabel.text?.toString()?.trim().takeIf { trigger == "detection" && !it.isNullOrBlank() }
            val confidence = sheetBinding.etConfidence.text?.toString()?.toDoubleOrNull().takeIf { trigger == "detection" }

            val condition = if (timeStart != null || timeEnd != null || label != null || confidence != null) {
                RuleCondition(
                    timeGte = timeStart,
                    timeLte = timeEnd,
                    payloadEq = label?.let { mapOf("label" to it) },
                    threshold = confidence?.let { mapOf("confidence" to NumberOp(op = ">=", value = it)) },
                )
            } else null

            val actionType = when (sheetBinding.rgActionType.checkedRadioButtonId) {
                R.id.rbActionMqtt -> "mqtt"
                R.id.rbActionWebhook -> "webhook"
                else -> "notify"
            }

            val action = when (actionType) {
                "notify" -> RuleAction(
                    type = "notify",
                    title = sheetBinding.etNotifyTitle.text?.toString()?.trim().takeIf { !it.isNullOrBlank() },
                    body = sheetBinding.etNotifyMessage.text?.toString()?.trim().takeIf { !it.isNullOrBlank() },
                )
                "mqtt" -> RuleAction(
                    type = "mqtt",
                    topic = sheetBinding.etMqttTopic.text?.toString()?.trim().takeIf { !it.isNullOrBlank() },
                    payload = sheetBinding.etMqttPayload.text?.toString()?.trim().takeIf { !it.isNullOrBlank() },
                )
                "webhook" -> RuleAction(
                    type = "webhook",
                    url = sheetBinding.etWebhookUrl.text?.toString()?.trim().takeIf { !it.isNullOrBlank() },
                )
                else -> RuleAction(type = "notify")
            }

            val cooldown = sheetBinding.etCooldown.text?.toString()?.toIntOrNull() ?: 60
            val throttle = RuleThrottle(cooldownS = cooldown)
            val enabled = sheetBinding.switchSheetEnabled.isChecked

            val token = container.prefsManager.token ?: return@setOnClickListener

            lifecycleScope.launch {
                try {
                    sheetBinding.btnSave.isEnabled = false
                    if (ruleToEdit == null) {
                        val createReq = CreateAutomationRuleRequest(
                            name = name,
                            trigger = trigger,
                            condition = condition,
                            action = action,
                            throttle = throttle,
                            enabled = enabled,
                        )
                        container.getRepository().createAutomationRule(token, createReq)
                        Toast.makeText(this@AutomationsActivity, "规则创建成功", Toast.LENGTH_SHORT).show()
                    } else {
                        val updateReq = UpdateAutomationRuleRequest(
                            name = name,
                            trigger = trigger,
                            condition = condition,
                            action = action,
                            throttle = throttle,
                            enabled = enabled,
                        )
                        container.getRepository().updateAutomationRule(token, ruleToEdit.id, updateReq)
                        Toast.makeText(this@AutomationsActivity, "规则更新成功", Toast.LENGTH_SHORT).show()
                    }
                    sheetDialog.dismiss()
                    loadData()
                } catch (e: Exception) {
                    Toast.makeText(this@AutomationsActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    sheetBinding.btnSave.isEnabled = true
                }
            }
        }

        sheetDialog.show()
    }
}
