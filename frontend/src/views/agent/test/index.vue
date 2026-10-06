<template>
  <div class="test-page">
    <section class="test-hero">
      <div>
        <div class="eyebrow">CONTRACT TEST AGENT</div>
        <h1>AI 自动化测试中心</h1>
        <p>从 OpenAPI 自动发现接口，生成正常、异常与边界用例，执行强弱契约断言并沉淀回归报告。</p>
      </div>
      <div class="hero-badges">
        <el-tag :type="capabilities?.online ? 'success' : 'danger'" effect="dark" round>
          {{ capabilities?.online ? '执行器在线' : '执行器离线' }}
        </el-tag>
        <el-tag type="info" effect="plain">{{ capabilities?.aiEnabled ? capabilities.model : '规则 + 契约模式' }}</el-tag>
      </div>
    </section>

    <section class="flow-grid">
      <div v-for="(step, index) in flow" :key="step.title" class="flow-card">
        <span>{{ index + 1 }}</span>
        <el-icon><component :is="step.icon" /></el-icon>
        <div><strong>{{ step.title }}</strong><small>{{ step.note }}</small></div>
      </div>
    </section>

    <section class="test-workspace">
      <el-card class="runner-panel" shadow="never">
        <template #header>
          <div class="panel-header"><div><strong>发起测试任务</strong><span>选择一个真实运行模块</span></div><el-tag type="primary">Live API</el-tag></div>
        </template>
        <el-form label-position="top">
          <el-form-item label="目标模块">
            <el-radio-group v-model="selectedModule" class="module-group">
              <el-radio-button value="mall-admin">运营后台</el-radio-button>
              <el-radio-button value="mall-portal">商城 API</el-radio-button>
              <el-radio-button value="agent-customer-scenarios">客服会话场景</el-radio-button>
              <el-radio-button value="agent-customer-quality">客服质量评测</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <div class="module-info">
            <el-icon><DocumentChecked /></el-icon>
            <div>
              <strong>{{ selectedModule }}</strong>
              <p>{{ moduleDescription }}</p>
            </div>
          </div>
          <el-button type="primary" size="large" :icon="VideoPlay" :loading="running" class="run-button" @click="runTests">
            {{ running ? 'Agent 正在生成并执行用例' : '生成并运行测试' }}
          </el-button>
          <p class="runner-tip">{{ runnerTip }}</p>
        </el-form>
      </el-card>

      <div class="report-column">
        <div class="score-grid">
          <div><span>最近通过率</span><strong :class="scoreClass">{{ latestReport ? `${latestReport.passRate.toFixed(1)}%` : '--' }}</strong></div>
          <div><span>用例数</span><strong>{{ latestReport?.totalTests ?? 0 }}</strong></div>
          <div><span>断言数</span><strong>{{ latestReport?.assertionsTotal ?? 0 }}</strong></div>
          <div><span>平均响应</span><strong>{{ latestReport ? `${Math.round(latestReport.averageResponseTime)} ms` : '--' }}</strong></div>
        </div>

        <el-card class="reports-panel" shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>测试报告</strong><span>失败用例会沉淀为下一轮已知缺陷</span></div>
              <el-button :icon="Refresh" circle @click="refreshReports" />
            </div>
          </template>

          <el-empty v-if="!reports.length" description="暂无报告，运行一次测试即可生成" />
          <div v-for="report in reports" :key="report.id" class="report-card" @click="openReport(report)">
            <div class="report-main">
              <div class="report-icon" :class="{ failed: report.failedTests > 0 }">
                <el-icon><CircleCheck v-if="report.failedTests === 0" /><Warning v-else /></el-icon>
              </div>
              <div>
                <strong>{{ report.moduleName }}</strong>
                <span>{{ formatTime(report.startTime) }} · {{ report.totalExecutionTime }} ms</span>
              </div>
            </div>
            <div class="report-progress">
              <el-progress :percentage="Math.round(report.passRate)" :status="report.failedTests ? 'exception' : 'success'" />
              <small>{{ report.passedTests }} 通过 / {{ report.failedTests }} 失败</small>
            </div>
            <el-button type="primary" link>查看 {{ report.totalTests }} 条结果</el-button>
          </div>
        </el-card>
      </div>
    </section>

    <el-dialog v-model="detailVisible" :title="`${selectedReport?.moduleName || ''} 测试详情`" width="880px">
      <div v-if="selectedReport" class="dialog-summary">
        <el-tag type="success">{{ selectedReport.assertionsPassed }} 个断言通过</el-tag>
        <el-tag :type="selectedReport.assertionsFailed ? 'danger' : 'info'">{{ selectedReport.assertionsFailed }} 个断言失败</el-tag>
        <span>报告 ID：{{ selectedReport.id.slice(0, 8) }}</span>
      </div>
      <el-alert
        v-if="selectedReport?.environment && !selectedReport.environment.skipped"
        class="env-alert"
        :type="selectedReport.environment.reachable ? 'success' : 'error'"
        :closable="false"
        show-icon
        :title="selectedReport.environment.reachable
          ? `环境可达：${selectedReport.environment.target}（${selectedReport.environment.detail || 'OK'}，${selectedReport.environment.latencyMs}ms）`
          : `环境不可达：${selectedReport.environment.target}（${selectedReport.environment.detail}）——本轮失败很可能来自环境而非被测代码`"
      />
      <el-table :data="selectedReport?.results || []" stripe max-height="500">
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.passed ? 'success' : 'danger'" size="small">{{ row.passed ? 'PASS' : 'FAIL' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="testCaseName" label="用例" min-width="220" />
        <el-table-column prop="actualStatusCode" label="状态码" width="90" align="center" />
        <el-table-column prop="executionTime" label="耗时(ms)" width="95" align="center" />
        <el-table-column label="断言/错误" min-width="260">
          <template #default="{ row }">
            <div v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</div>
            <div v-for="assertion in row.assertionDetails || []" :key="assertion.assertionName" :class="assertion.passed ? 'assert-pass' : 'assert-fail'">
              {{ assertion.passed ? '✓' : '✗' }} {{ assertion.assertionName }}：{{ assertion.message }}
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CircleCheck, Connection, DataAnalysis, DocumentChecked, Refresh, Tickets, VideoPlay, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  generateTestReportAPI,
  getAllTestReportsAPI,
  getTestCapabilitiesAPI,
  type TestCapabilities,
  type TestReport,
} from '@/apis/agent'

const selectedModule = ref('mall-portal')
const running = ref(false)
const reports = ref<TestReport[]>([])
const capabilities = ref<TestCapabilities | null>(null)
const detailVisible = ref(false)
const selectedReport = ref<TestReport | null>(null)

const MODULE_DESCRIPTIONS: Record<string, string> = {
  'mall-admin': '发现后台管理 OpenAPI，并通过网关执行用例。',
  'mall-portal': '发现会员、商品、购物车和订单接口。',
  'agent-customer-scenarios': '执行客服 Agent 会话场景：意图路由 / 工具取数 / 知识来源 / 拒答与鉴权边界。',
  'agent-customer-quality': '在 gold 评测集上评测客服 Agent 质量：来源命中 / 拒答正确 / 注入阻断，开启本地模型后追加答案正确性与忠实性打分。',
}

const MODULE_TIPS: Record<string, string> = {
  'agent-customer-scenarios': '会话场景用例均为只读问答（含未登录鉴权边界），不修改业务数据。',
  'agent-customer-quality': '质量评测为只读问答；单条用例需真实调用一次大模型（约 15-25s），默认按类别轮询取 12 条。',
}

const moduleDescription = computed(() => MODULE_DESCRIPTIONS[selectedModule.value] ?? MODULE_DESCRIPTIONS['mall-portal'])
const runnerTip = computed(() => MODULE_TIPS[selectedModule.value] ?? '安全演示模式：最多执行 8 个只读 GET 契约，不修改业务数据。')
const flow = [
  { title: 'API 发现', note: '读取 OpenAPI 3 契约', icon: Connection },
  { title: '用例生成', note: '正常 / 异常 / 边界', icon: Tickets },
  { title: '自动执行', note: '真实 HTTP + 连接池', icon: VideoPlay },
  { title: '契约断言', note: '状态码 / Schema / 语义', icon: DocumentChecked },
  { title: '报告洞察', note: '缺陷回流与回归', icon: DataAnalysis },
]

const latestReport = computed(() => reports.value[0] || null)
const scoreClass = computed(() => latestReport.value && latestReport.value.passRate < 80 ? 'danger' : 'success')

const runTests = async () => {
  running.value = true
  try {
    const response = await generateTestReportAPI(selectedModule.value)
    ElMessage.success(`测试完成：${response.data.passedTests}/${response.data.totalTests} 通过`)
    await refreshReports()
    openReport(response.data)
  } catch (error: any) {
    ElMessage.error(`测试 Agent 执行失败：${error?.message || '请检查 8085 服务'}`)
  } finally {
    running.value = false
  }
}

const refreshReports = async () => {
  try {
    const response = await getAllTestReportsAPI()
    reports.value = [...(response.data || [])].sort(
      (a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime(),
    )
  } catch {
    reports.value = []
  }
}

const loadCapabilities = async () => {
  try {
    const response = await getTestCapabilitiesAPI()
    capabilities.value = response.data
  } catch {
    capabilities.value = null
  }
}

const openReport = (report: TestReport) => {
  selectedReport.value = report
  detailVisible.value = true
}
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'

onMounted(() => Promise.all([refreshReports(), loadCapabilities()]))
</script>

<style scoped>
.test-page { padding: 22px; min-height: calc(100vh - 84px); color: #192237; background: #f4f7fb; }
.test-hero { display: flex; justify-content: space-between; align-items: center; padding: 27px 30px; color: white; border-radius: 18px; background: radial-gradient(circle at 18% 30%, rgba(67,209,167,.25), transparent 28%), linear-gradient(120deg, #142633, #164b55 55%, #1e7370); box-shadow: 0 18px 42px rgba(21,78,84,.18); }.eyebrow { color: #75efd1; font-size: 12px; font-weight: 800; letter-spacing: 2px; }.test-hero h1 { margin: 7px 0; font-size: 28px; }.test-hero p { margin: 0; color: #d5eeea; }.hero-badges { display: flex; gap: 8px; }
.flow-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin: 16px 0; }.flow-card { display: flex; align-items: center; gap: 10px; padding: 14px; border: 1px solid #e3e8f0; border-radius: 12px; background: white; }.flow-card > span { color: #3b9d8b; font-size: 10px; font-weight: 800; }.flow-card > .el-icon { color: #278d7d; font-size: 19px; }.flow-card strong, .flow-card small { display: block; }.flow-card strong { font-size: 13px; }.flow-card small { margin-top: 3px; color: #929aab; font-size: 10px; }
.test-workspace { display: grid; grid-template-columns: 330px minmax(0, 1fr); gap: 16px; }.runner-panel, .reports-panel { border: 1px solid #e3e8f0; border-radius: 15px; }.panel-header { display: flex; justify-content: space-between; align-items: center; }.panel-header strong, .panel-header span { display: block; }.panel-header span { margin-top: 4px; color: #929aab; font-size: 11px; }.module-group { width: 100%; }.module-group :deep(.el-radio-button) { width: 50%; }.module-group :deep(.el-radio-button__inner) { width: 100%; }.module-info { display: flex; gap: 11px; margin: 8px 0 20px; padding: 14px; color: #2c746b; border-radius: 11px; background: #edf9f6; }.module-info .el-icon { margin-top: 2px; font-size: 20px; }.module-info p { margin: 4px 0 0; font-size: 11px; line-height: 1.55; }.run-button { width: 100%; }.runner-tip { color: #9aa2b2; font-size: 10px; text-align: center; }
.report-column { min-width: 0; }.score-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }.score-grid > div { padding: 14px 16px; border: 1px solid #e3e8f0; border-radius: 12px; background: white; }.score-grid span, .score-grid strong { display: block; }.score-grid span { color: #8790a3; font-size: 11px; }.score-grid strong { margin-top: 5px; font-size: 21px; }.score-grid .success { color: #28a574; }.score-grid .danger { color: #d75959; }
.reports-panel :deep(.el-card__body) { max-height: 550px; overflow-y: auto; }.report-card { display: grid; grid-template-columns: minmax(220px, 1fr) 240px 120px; align-items: center; gap: 20px; margin-bottom: 10px; padding: 14px; border: 1px solid #e4e8ef; border-radius: 12px; cursor: pointer; transition: .2s; }.report-card:hover { transform: translateY(-1px); border-color: #91cabc; box-shadow: 0 8px 20px rgba(38,120,103,.08); }.report-main { display: flex; align-items: center; gap: 11px; }.report-main strong, .report-main span { display: block; }.report-main span { margin-top: 4px; color: #929aab; font-size: 10px; }.report-icon { display: grid; place-items: center; width: 38px; height: 38px; color: #249568; border-radius: 11px; background: #e9f8f1; }.report-icon.failed { color: #d45757; background: #fff0f0; }.report-progress small { display: block; margin-top: 3px; color: #929aab; font-size: 10px; }.dialog-summary { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }.env-alert { margin-bottom: 12px; }.dialog-summary span { margin-left: auto; color: #929aab; font-size: 11px; }.error-text, .assert-fail { color: #d65454; font-size: 11px; }.assert-pass { color: #28986e; font-size: 11px; }
@media (max-width: 1150px) { .flow-grid { grid-template-columns: repeat(3, 1fr); }.test-workspace { grid-template-columns: 1fr; }.report-card { grid-template-columns: 1fr; }.test-hero { align-items: flex-start; flex-direction: column; gap: 14px; } }
</style>
