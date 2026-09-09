package me.hebin.focus.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import me.hebin.focus.data.CollectionRepository
import me.hebin.focus.data.RewardApi
import me.hebin.focus.data.StubRewardApi
import me.hebin.focus.databinding.ActivityRewardBinding

/** 奖励兑换页：当前为预留接口演示（StubRewardApi），后续接后端 */
class RewardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRewardBinding
    private lateinit var api: RewardApi

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        binding = ActivityRewardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.pad(binding.root)

        api = StubRewardApi(CollectionRepository.get(this))
        val set = api.rewardSets().firstOrNull() ?: run { finish(); return }

        binding.textRewardTitle.text = set.title
        binding.textRewardDesc.text = set.description

        refresh()

        binding.btnExchange.setOnClickListener {
            binding.btnExchange.isEnabled = false
            api.exchange(set.id) { result ->
                mainHandler.post {
                    binding.btnExchange.isEnabled = true
                    AlertDialog.Builder(this)
                        .setTitle(if (result.success) "兑换成功" else "暂时无法兑换")
                        .setMessage(result.message)
                        .setPositiveButton("好的") { d, _ -> d.dismiss() }
                        .show()
                }
            }
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        val set = api.rewardSets().first() ?: return
        val e = api.eligibility(set.id)
        binding.progressReward.max = e.required
        binding.progressReward.progress = e.collected
        binding.textRewardProgress.text = "${e.collected} / ${e.required}"
        binding.textStatus.text = e.message
        binding.btnExchange.isEnabled = e.eligible
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
