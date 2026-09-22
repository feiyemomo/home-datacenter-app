package com.homedatacenter.app.ui.automations

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.data.model.AutomationRule
import com.homedatacenter.app.databinding.ItemAutomationRuleBinding

class AutomationRuleAdapter(
    private val onToggle: (AutomationRule, Boolean) -> Unit,
    private val onTest: (AutomationRule) -> Unit,
    private val onEdit: (AutomationRule) -> Unit,
    private val onDelete: (AutomationRule) -> Unit,
) : ListAdapter<AutomationRule, AutomationRuleAdapter.RuleViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemAutomationRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RuleViewHolder(
        private val binding: ItemAutomationRuleBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: AutomationRule) {
            binding.tvRuleName.text = rule.name

            // Switch toggle
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = rule.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }

            // Trigger Chip
            val triggerText = when (rule.trigger) {
                "detection" -> "触发: 目标检测"
                "alert" -> "触发: 安全告警"
                "camera.offline" -> "触发: 摄像头离线"
                "camera.online" -> "触发: 摄像头上线"
                else -> "触发: ${rule.trigger}"
            }
            binding.chipTrigger.text = triggerText

            // Action Chip
            val action = rule.action
            val actionType = action?.type ?: "notify"
            val actionText = when (actionType) {
                "notify" -> "动作: 系统通知"
                "mqtt" -> "动作: MQTT 指令"
                "webhook" -> "动作: Webhook"
                else -> "动作: $actionType"
            }
            binding.chipAction.text = actionText

            // Condition summary
            val conditionParts = mutableListOf<String>()
            val cond = rule.condition
            if (cond != null) {
                if (!cond.startTime.isNullOrBlank() || !cond.endTime.isNullOrBlank()) {
                    conditionParts.add("时段 ${cond.startTime.orEmpty()} - ${cond.endTime.orEmpty()}")
                }
                if (!cond.label.isNullOrBlank()) {
                    conditionParts.add("识别 ${cond.label}")
                }
                if (cond.confidence != null && cond.confidence!! > 0.0) {
                    val pct = (cond.confidence!! * 100).toInt()
                    conditionParts.add("置信度 ≥ $pct%")
                }
            }
            if (conditionParts.isNotEmpty()) {
                binding.tvCondition.visibility = View.VISIBLE
                binding.tvCondition.text = "条件: " + conditionParts.joinToString(" · ")
            } else {
                binding.tvCondition.visibility = View.GONE
            }

            // Action details summary
            val actionDetail = when (action?.type) {
                "notify" -> {
                    val title = action.title.orEmpty().ifBlank { "系统告警" }
                    "通知: $title"
                }
                "mqtt" -> {
                    val topic = action.topic.orEmpty()
                    "发布: $topic"
                }
                "webhook" -> {
                    val url = action.url.orEmpty()
                    "调用: $url"
                }
                else -> ""
            }
            if (actionDetail.isNotEmpty()) {
                binding.tvActionDetail.visibility = View.VISIBLE
                binding.tvActionDetail.text = actionDetail
            } else {
                binding.tvActionDetail.visibility = View.GONE
            }

            // Fire stats & cooldown
            val cooldown = rule.throttle?.cooldownSeconds ?: 0
            binding.tvFireStats.text = "已触发 ${rule.fireCount} 次 · 冷却 ${cooldown}s"

            // Buttons
            binding.btnTest.setOnClickListener { onTest(rule) }
            binding.btnEdit.setOnClickListener { onEdit(rule) }
            binding.btnDelete.setOnClickListener { onDelete(rule) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<AutomationRule>() {
            override fun areItemsTheSame(oldItem: AutomationRule, newItem: AutomationRule): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: AutomationRule, newItem: AutomationRule): Boolean {
                return oldItem == newItem
            }
        }
    }
}
