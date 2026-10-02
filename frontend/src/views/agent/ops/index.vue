<template>
  <div class="ops-page">
    <section class="ops-hero">
      <div>
        <div class="eyebrow">AUTONOMOUS AIOPS</div>
        <h1>智能运维多 Agent 控制台</h1>
        <p>异常检测、知识图谱根因定位、自愈 Playbook 与变更审批组成可审计的闭环流水线。</p>
      </div>
      <div class="hero-status">
        <el-tag :type="capabilities?.online ? 'success' : 'danger'" effect="dark" round>
          {{ capabilities?.online ? '编排器在线' : '编排器离线' }}
        </el-tag>
        <span>{{ capabilities?.llmModel || 'qwen3.5-noVL:latest' }}</span>
      </div>
    </section>

    <section class="pipeline-strip">
      <div v-for="(stage, index) in pipelineStages" :key="stage.title" class="stage-card">
        <div class="stage-index">0{{ index + 1 }}</div>
        <div>
          <strong>{{ stage.title }}</strong>
          <span>{{ stage.subtitle }}</span>
        </div>
        <el-icon v-if="index < pipelineStages.length - 1" class="arrow"><Right /></el-icon>
      </div>
    </section>

    <section class="dashboard-grid">
      <el-card class="trigger-panel" shadow="never">
        <template #header>
          <div class="panel-header">
            <div><strong>故障注入实验</strong><span>构造指标异常，观察多 Agent 联动</span></div>
            <el-tag type="warning" effect="plain">Dry Run</el-tag>
          </div>
        </template>
        <el-form label-position="top" :model="triggerForm">
          <el-form-item label="目标服务">
            <el-select v-model="triggerForm.target_service" style="width: 100%">
              <el-option label="订单服务 order-service" value="order-service" />
              <el-option label="网关 api-gateway" value="api-gateway" />
              <el-option label="商品服务 product-service" value="product-service" />
              <el-option label="MySQL 主库 mysql-primary" value="mysql-primary" />
            </el-select>
          </el-form-item>
          <el-form-item label="异常指标">
            <el-select v-model="triggerForm.metric_name" style="width: 100%">
              <el-option label="CPU 使用率" value="cpu_usage_percent" />
              <el-option label="内存使用率" value="memory_usage_percent" />
              <el-option label="磁盘使用率" value="disk_usage_percent" />
              <el-option label="网络延迟" value="network_latency_ms" />
            </el-select>
          </el-form-item>
          <el-form-item label="观测值">
            <div class="metric-input">
              <el-slider v-model="triggerForm.metric_value" :min="0" :max="100" :step="1" />
              <strong>{{ triggerForm.metric_value }}</strong>
            </div>
          </el-form-item>
          <el-button type="danger" size="large" :icon="WarnTriangleFilled" :loading="triggering" class="trigger-button" @click="triggerIncident">
            触发异常处置链路
          </el-button>
        </el-form>

        <div class="algorithm-box">
          <span v-for="algorithm in capabilities?.algorithms || defaultAlgorithms" :key="algorithm">{{ algorithm }}</span>
        </div>
      </el-card>

      <div class="incident-column">
        <div class="summary-grid">
          <div><span>事件总数</span><strong>{{ incidents.length }}</strong></div>
          <div><span>已自动闭环</span><strong class="success">{{ resolvedCount }}</strong></div>
          <div><span>待人工审批</span><strong class="warning">{{ pendingCount }}</strong></div>
          <div><span>平均置信度</span><strong>{{ averageConfidence }}%</strong></div>
        </div>

        <el-card class="incident-panel" shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>处置时间线</strong><span>算法结论与 Ollama 摘要同时展示</span></div>
              <el-button :icon="Refresh" circle @click="refreshIncidents" />
            </div>
          </template>

          <el-empty v-if="!incidents.length" description="暂无故障事件，先从左侧注入一个异常" />
          <div v-for="incident in incidents" :key="incident.id" class="incident-card">
            <div class="incident-topline">
              <div>
                <el-tag :type="severityType(incident.alert?.severity)" effect="dark" size="small">
                  {{ incident.alert?.severity || 'UNKNOWN' }}
                </el-tag>
                <strong>{{ incident.alert?.targetService }}</strong>
                <span>{{ incident.alert?.metricName }} = {{ incident.alert?.metricValue }}</span>
              </div>
              <el-tag :type="statusType(incident.status)" round>{{ statusLabel(incident.status) }}</el-tag>
            </div>

            <el-steps :active="activeStep(incident.status)" finish-status="success" simple class="incident-steps">
              <el-step title="检测" />
              <el-step title="根因" />
              <el-step title="自愈" />
              <el-step title="门控" />
            </el-steps>

            <div class="incident-details">
              <div class="detail-block rca-block">
                <span>RCA · Bayesian + Graph</span>
                <strong>{{ incident.rcaResult?.rootCause || '分析中' }}</strong>
                <p>{{ incident.rcaResult?.analysisSummary || '等待根因分析结果…' }}</p>
                <small>置信度 {{ Math.round((incident.rcaResult?.confidence || 0) * 100) }}% · 影响链 {{ incident.rcaResult?.impactChain?.join(' → ') || '-' }}</small>
              </div>
              <div class="detail-block">
                <span>Heal · Playbook</span>
                <strong>{{ incident.healAction?.playbook || '规划中' }}</strong>
                <p>{{ incident.healAction?.level || '-' }} · 爆炸半径 {{ Math.round((incident.healAction?.blastRadius || 0) * 100) }}%</p>
                <small>{{ incident.healAction?.dryRunPassed ? 'Dry-run 已通过' : '等待预演' }}</small>
              </div>
              <div class="detail-block">
                <span>Change Gate</span>
                <strong>{{ incident.changeDecision?.approver || '评估中' }}</strong>
                <p>风险 {{ Math.round((incident.changeDecision?.riskScore || 0) * 100) }}% · {{ incident.changeDecision?.status || '-' }}</p>
                <small>{{ incident.changeDecision?.reason || '等待审批门控' }}</small>
              </div>
            </div>
          </div>
        </el-card>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh, Right, WarnTriangleFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { TagProps } from 'element-plus'
import {
  getAllIncidentsAPI,
  getOpsCapabilitiesAPI,
  triggerIncidentAPI,
  type IncidentState,
  type OpsCapabilities,
} from '@/apis/agent'

const triggerForm = ref({ metric_name: 'cpu_usage_percent', metric_value: 95, target_service: 'order-service', demo_mode: true })
const triggering = ref(false)
const incidents = ref<IncidentState[]>([])
const capabilities = ref<OpsCapabilities | null>(null)
const defaultAlgorithms = ['3-Sigma', 'EWMA', 'Bayesian Inference', 'Graph Traversal']
const pipelineStages = [
  { title: 'Monitor Agent', subtitle: '3-Sigma + EWMA' },
  { title: 'RCA Agent', subtitle: 'Bayesian + Neo4j' },
  { title: 'Heal Agent', subtitle: 'Playbook + Dry Run' },
  { title: 'Change Agent', subtitle: 'Risk Gate + Audit' },
]

const resolvedCount = computed(() => incidents.value.filter(item => item.status === 'resolved').length)
const pendingCount = computed(() => incidents.value.filter(item => item.status === 'pending_approval').length)
const averageConfidence = computed(() => {
  const values = incidents.value.map(item => item.rcaResult?.confidence || 0).filter(Boolean)
  return values.length ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length * 100) : 0
})

const triggerIncident = async () => {
  triggering.value = true
  try {
    const response = await triggerIncidentAPI(triggerForm.value)
    if (!response.data) {
      ElMessage.info('当前指标未达到异常阈值')
      return
    }
    ElMessage.success('多 Agent 处置链路执行完成')
    await refreshIncidents()
  } catch (error: any) {
    ElMessage.error(`运维 Agent 调用失败：${error?.message || '请检查 8084 服务'}`)
  } finally {
    triggering.value = false
  }
}

const refreshIncidents = async () => {
  try {
    const response = await getAllIncidentsAPI()
    incidents.value = Object.values(response.data || {}).sort(
      (a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime(),
    )
  } catch {
    incidents.value = []
  }
}

const loadCapabilities = async () => {
  try {
    const response = await getOpsCapabilitiesAPI()
    capabilities.value = response.data
  } catch {
    capabilities.value = null
  }
}

const severityType = (severity?: string): TagProps['type'] => {
  return ({ CRITICAL: 'danger', HIGH: 'warning', MEDIUM: 'primary', LOW: 'info' } as Record<string, TagProps['type']>)[severity || ''] || 'info'
}
const statusType = (status: string): TagProps['type'] => {
  return ({ resolved: 'success', pending_approval: 'warning', healing: 'primary', analyzed: 'primary', detected: 'danger' } as Record<string, TagProps['type']>)[status] || 'info'
}
const statusLabel = (status: string) => ({ resolved: '已自动闭环', pending_approval: '待人工审批', healing: '自愈中', analyzed: '已定位根因', detected: '已检测' } as Record<string, string>)[status] || status
const activeStep = (status: string) => ({ detected: 1, analyzed: 2, healing: 3, resolved: 4, pending_approval: 4 } as Record<string, number>)[status] || 1

onMounted(() => Promise.all([refreshIncidents(), loadCapabilities()]))
</script>

<style scoped>
.ops-page { padding: 22px; min-height: calc(100vh - 84px); color: #182034; background: #f4f7fb; }
.ops-hero { display: flex; justify-content: space-between; align-items: center; padding: 27px 30px; color: white; border-radius: 18px; background: radial-gradient(circle at 78% 30%, rgba(255,113,77,.25), transparent 28%), linear-gradient(120deg, #191b2f, #472a50 52%, #813b3d); box-shadow: 0 18px 42px rgba(71,42,80,.18); }
.eyebrow { color: #ffb89c; font-size: 12px; font-weight: 800; letter-spacing: 2px; }.ops-hero h1 { margin: 7px 0; font-size: 28px; }.ops-hero p { margin: 0; color: #f2dde4; }.hero-status { display: flex; align-items: center; gap: 12px; }.hero-status span { color: #f7d7cd; font-size: 12px; }
.pipeline-strip { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 16px 0; }.stage-card { position: relative; display: flex; align-items: center; gap: 12px; padding: 15px; border: 1px solid #e4e8f0; border-radius: 13px; background: white; }.stage-index { color: #c85d4f; font-size: 12px; font-weight: 800; }.stage-card strong, .stage-card span { display: block; }.stage-card strong { font-size: 14px; }.stage-card span { margin-top: 3px; color: #929aab; font-size: 11px; }.arrow { position: absolute; right: -12px; z-index: 2; color: #b6bdcb; }
.dashboard-grid { display: grid; grid-template-columns: 340px minmax(0, 1fr); gap: 16px; }.trigger-panel, .incident-panel { border: 1px solid #e3e8f0; border-radius: 15px; }.panel-header { display: flex; justify-content: space-between; align-items: center; }.panel-header strong, .panel-header span { display: block; }.panel-header span { margin-top: 4px; color: #929aab; font-size: 11px; }
.metric-input { display: flex; align-items: center; gap: 15px; width: 100%; }.metric-input .el-slider { flex: 1; }.metric-input strong { display: grid; place-items: center; width: 48px; height: 34px; color: #a13c38; border-radius: 8px; background: #fff0ee; }.trigger-button { width: 100%; }.algorithm-box { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 18px; padding-top: 15px; border-top: 1px dashed #dfe4ed; }.algorithm-box span { padding: 5px 8px; color: #586278; border-radius: 6px; background: #f1f3f8; font-size: 10px; }
.incident-column { min-width: 0; }.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }.summary-grid > div { padding: 14px 16px; border: 1px solid #e4e8f0; border-radius: 12px; background: white; }.summary-grid span, .summary-grid strong { display: block; }.summary-grid span { color: #858ea1; font-size: 11px; }.summary-grid strong { margin-top: 5px; font-size: 22px; }.summary-grid .success { color: #27a36d; }.summary-grid .warning { color: #ce7b32; }
.incident-panel :deep(.el-card__body) { max-height: 590px; overflow-y: auto; }.incident-card { margin-bottom: 13px; padding: 17px; border: 1px solid #e5e8ef; border-radius: 13px; background: #fbfcfe; }.incident-topline { display: flex; justify-content: space-between; align-items: center; }.incident-topline > div { display: flex; align-items: center; gap: 10px; }.incident-topline span { color: #7f889a; font-size: 12px; }.incident-steps { margin: 15px 0; }.incident-details { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 10px; }.detail-block { padding: 12px; border-radius: 10px; background: white; }.detail-block > span { color: #929aab; font-size: 10px; text-transform: uppercase; }.detail-block strong { display: block; margin: 6px 0; color: #293149; font-size: 13px; }.detail-block p { margin: 0 0 5px; color: #5c667a; font-size: 11px; line-height: 1.55; }.detail-block small { color: #9aa2b1; font-size: 10px; }.rca-block { border-left: 3px solid #6b62cc; }
@media (max-width: 1150px) { .dashboard-grid { grid-template-columns: 1fr; }.pipeline-strip { grid-template-columns: repeat(2, 1fr); }.incident-details { grid-template-columns: 1fr; }.ops-hero { align-items: flex-start; flex-direction: column; gap: 15px; } }
</style>
