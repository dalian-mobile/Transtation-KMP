package com.funny.translation.translate.ui.thanks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.funny.compose.loading.LoadingState
import com.funny.data_saver.core.mutableDataSaverListStateOf
import com.funny.translation.AppConfig
import com.funny.translation.helper.DataSaverUtils
import com.funny.translation.helper.get
import com.funny.translation.network.api
import com.funny.translation.strings.ResStrings
import com.funny.translation.translate.database.TransHistoryBean
import com.funny.translation.translate.database.TransHistoryDao
import com.funny.translation.translate.database.appDB
import com.funny.translation.translate.database.transHistoryDao
import com.funny.translation.translate.findLanguageById
import com.funny.translation.translate.network.TransNetwork
import com.funny.translation.translate.network.service.LLMAnalyzeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import kotlin.time.Duration
import kotlin.time.measureTime

class AnnualReportViewModel: ViewModel() {
    companion object{
        private const val YEAR = 2024
        private val calendar by lazy {
            Calendar.getInstance()
        }
        // 2024年全年，开始和结束对应的时间戳
        val START_TIME by lazy(LazyThreadSafetyMode.PUBLICATION) {
            // 1月1日 0点
            calendar.apply {
                set(YEAR, 0, 1, 0, 0, 0)
            }.timeInMillis
        }

        val END_TIME by lazy(LazyThreadSafetyMode.PUBLICATION) {
            // 12月31日 23点59分59秒
            calendar.apply {
                set(YEAR, 11, 31, 23, 59, 59)
            }.timeInMillis
        }
    }
    var shouldLoadLatest = false

    var loadingState: LoadingState<Unit> = LoadingState.Loading
    var loadingDuration: Duration = Duration.ZERO

    var totalTranslateTimes by mutableStateOf( 0)
    var earliestTime by mutableStateOf( 0L)
    var latestTime by mutableStateOf( 0L)
    var totalTranslateWords by mutableStateOf( 0)
    var mostCommonSourceLanguage by mutableStateOf( "")
    var mostCommonTargetLanguage by mutableStateOf( "")
    var mostCommonSourceLanguageTimes by mutableStateOf( 0)
    var mostCommonTargetLanguageTimes by mutableStateOf( 0)
    var enginesUsesList by mutableDataSaverListStateOf(DataSaverUtils, "annual_report_engines_uses_list", listOf<Pair<String, Int>>())
    var llmAnalyzeResult by mutableStateOf( LLMAnalyzeResult() )
    private val transHistoryDao: TransHistoryDao = appDB.transHistoryDao
    private val analyzeService = TransNetwork.analyzeService

    suspend fun loadAnnualReport() = withContext(Dispatchers.IO){
        if (loadingState is LoadingState.Success) {
            return@withContext
        }
        if (loadingState is LoadingState.Loading) {
            loadingDuration = measureTime {
                // 从数据库中读取各种值
                var allHistories = transHistoryDao.queryAllBetween(START_TIME, END_TIME)
                if (allHistories.isEmpty()) {
                    allHistories = transHistoryDao.queryAllBetween(END_TIME, System.currentTimeMillis())
                    shouldLoadLatest = true
                }
                if (allHistories.isEmpty()) {
                    throw Exception(ResStrings.no_data)
                }
                totalTranslateTimes = allHistories.size
                val specialTimes = allHistories.findSpecialTimes()
                earliestTime = specialTimes.first
                latestTime = specialTimes.second
                totalTranslateWords = allHistories.sumOf { it.sourceString.length }
                val usesSourceLanguageMap = mutableMapOf<Int, Int>()
                val usesTargetLanguageMap = mutableMapOf<Int, Int>()
                val usesEngineMap = mutableMapOf<String, Int>()
                // 语言使用次数和引擎使用次数
                allHistories.forEach {
                    usesSourceLanguageMap[it.sourceLanguageId] =
                        usesSourceLanguageMap.get(it.sourceLanguageId, 0) + 1
                    usesTargetLanguageMap[it.targetLanguageId] =
                        usesTargetLanguageMap.get(it.targetLanguageId, 0) + 1
                    it.engineNames.forEach { engineName ->
                        usesEngineMap[engineName] = usesEngineMap.get(engineName, 0) + 1
                    }
                }
                // 计算最常用的语言
                val mostCommonSourceLanguageId = usesSourceLanguageMap.keyOfMaxValue()
                val mostCommonTargetLanguageId = usesTargetLanguageMap.keyOfMaxValue()
                mostCommonSourceLanguage =
                    findLanguageById(mostCommonSourceLanguageId).displayText
                mostCommonTargetLanguage =
                    findLanguageById(mostCommonTargetLanguageId).displayText
                mostCommonSourceLanguageTimes =
                    usesSourceLanguageMap[mostCommonSourceLanguageId] ?: 0
                mostCommonTargetLanguageTimes =
                    usesTargetLanguageMap[mostCommonTargetLanguageId] ?: 0
                // 计算引擎使用次数
                val engineUsesList = usesEngineMap.toList().sortedByDescending { it.second }
                enginesUsesList = engineUsesList

                // LLM分析结果
                if (AppConfig.uid > 0) {
                    api(analyzeService::getLLMData, AppConfig.uid, START_TIME, END_TIME) {
                        success {  }
                    }?.let {
                        llmAnalyzeResult = it
                    }
                }
            }
            loadingState = LoadingState.Success(Unit)
        }
    }
}

// 根据历史记录，找到一天中的最早时间和最晚时间
private fun List<TransHistoryBean>.findSpecialTimes(): Pair<Long, Long> {
    // 计算出每一个对应的年月日、时分秒，找到时分秒最少的那一个
    var earliestTimeOfDay: Int = Int.MAX_VALUE
    var earliestTimeIndex = -1
    var latestTimeOfDay: Int = Int.MIN_VALUE
    var latestTimeIndex = -1
    this.forEachIndexed { index, transHistoryBean ->
        val calendar = Calendar.getInstance().apply {
            time = Date(transHistoryBean.time)
        }
        var hour = calendar.get(Calendar.HOUR_OF_DAY)
        // 凌晨 5 点前被当做晚上
        if (hour <= 5) hour += 24

        val t = hour * 3600 +
                calendar.get(Calendar.MINUTE) * 60 +
                calendar.get(Calendar.SECOND)

        if (t < earliestTimeOfDay) {
            earliestTimeOfDay = t
            earliestTimeIndex = index
        }
        if (t > latestTimeOfDay) {
            latestTimeOfDay = t
            latestTimeIndex = index
        }
    }
    return this[earliestTimeIndex].time to this[latestTimeIndex].time

}

private fun MutableMap<Int, Int>.keyOfMaxValue(): Int {
    var maxKey = -1
    var maxValue = Int.MIN_VALUE
    this.forEach { (key, value) ->
        if (value > maxValue) {
            maxKey = key
            maxValue = value
        }
    }
    return maxKey
}