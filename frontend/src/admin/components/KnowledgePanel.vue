<template>
  <div class="knowledge-root">
    <!-- ── 1. 顶部资产与健康大盘卡片 ── -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon-wrap blue">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" stroke="currentColor" stroke-width="2"/>
          </svg>
        </div>
        <div class="stat-content">
          <div class="stat-val">{{ overview.totalDocuments || 0 }} <span class="unit">篇</span></div>
          <div class="stat-label">知识库文档资产总量</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon-wrap purple">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <rect x="3" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="2"/>
            <rect x="14" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="2"/>
            <rect x="14" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="2"/>
            <rect x="3" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="2"/>
          </svg>
        </div>
        <div class="stat-content">
          <div class="stat-val">{{ overview.totalChunks || 0 }} <span class="unit">个切片</span></div>
          <div class="stat-label">结构化 HNSW 向量索引</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon-wrap green">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/>
            <path d="M8 12l2.5 2.5L16 9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-content">
          <div class="stat-val text-green">ES 8.15 就绪</div>
          <div class="stat-label">IK 分词 + 稠密向量混合召回</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon-wrap orange">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="stat-content">
          <div class="stat-val text-orange">{{ overview.semanticCache?.hitRate || 0 }}%</div>
          <div class="stat-label">Redis 语义缓存命中率 (20ms)</div>
        </div>
      </div>
    </div>

    <!-- ── 2. 子功能视图切换导航 ── -->
    <div class="sub-nav-bar">
      <div class="sub-tabs">
        <button
          class="sub-tab-btn"
          :class="{ active: currentSubTab === 'docs' }"
          @click="currentSubTab = 'docs'"
        >
          📄 知识资产全生命周期治理 (CRUD)
        </button>
        <button
          class="sub-tab-btn"
          :class="{ active: currentSubTab === 'playground' }"
          @click="currentSubTab = 'playground'"
        >
          🔍 RAG 检索在线调试沙盒 (Playground)
        </button>
        <button
          class="sub-tab-btn"
          :class="{ active: currentSubTab === 'metrics' }"
          @click="currentSubTab = 'metrics'"
        >
          📊 RAG 三元组评测大盘
        </button>
      </div>

      <div class="sub-actions">
        <button class="btn-sync" :disabled="syncing" @click="handleSyncAll">
          <span v-if="syncing" class="spin">⟳</span>
          <span>{{ syncing ? '全量重建同步中…' : '⚡ 一键同步向量库' }}</span>
        </button>
        <button class="btn-upload" @click="showUploadModal = true">
          <span>+ 拖拽解析上传文档</span>
        </button>
      </div>
    </div>

    <!-- ── 3. 子视图 A：知识文档治理 (CRUD) ── -->
    <div v-if="currentSubTab === 'docs'" class="view-panel">
      <!-- 过滤搜索栏 -->
      <div class="filter-bar">
        <div class="category-pills">
          <button
            v-for="cat in categoryOptions"
            :key="cat.key"
            class="pill-btn"
            :class="{ active: activeCategory === cat.key }"
            @click="setCategory(cat.key)"
          >
            {{ cat.label }}
            <span class="pill-badge">{{ getCategoryCount(cat.key) }}</span>
          </button>
        </div>

        <div class="search-wrap">
          <input
            v-model="keyword"
            type="text"
            placeholder="按文档名称 / 知识点 / 出处搜索…"
            class="search-input"
            @keyup.enter="loadDocs"
          />
          <button class="btn-search" @click="loadDocs">查询</button>
        </div>
      </div>

      <!-- 文档列表表格 -->
      <div class="table-container">
        <table class="doc-table">
          <thead>
            <tr>
              <th>文档标识与文件名</th>
              <th>业务分类</th>
              <th>绑定知识点</th>
              <th>切片数</th>
              <th>大小</th>
              <th>最新更新时间</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loadingDocs">
              <td colspan="7" class="loading-cell">正在加载知识库资产…</td>
            </tr>
            <tr v-else-if="docList.length === 0">
              <td colspan="7" class="empty-cell">未找到匹配的知识库文档，点击右上角上传新文档。</td>
            </tr>
            <tr v-for="doc in docList" :key="doc.docId" class="doc-row">
              <td class="name-cell">
                <span class="doc-icon">📄</span>
                <div class="name-text-group">
                  <span class="doc-name" :title="doc.filename">{{ doc.filename }}</span>
                  <span v-if="doc.source && doc.source !== doc.filename" class="doc-source-sub" :title="doc.source">{{ doc.source }}</span>
                </div>
              </td>
              <td>
                <span class="cat-tag" :class="doc.category.toLowerCase()">
                  {{ categoryLabel(doc.category) }}
                </span>
              </td>
              <td><span class="kp-badge">{{ doc.kp }}</span></td>
              <td><span class="chunk-badge">{{ doc.chunkCount }} 块</span></td>
              <td class="size-cell">{{ formatSize(doc.sizeBytes) }}</td>
              <td class="time-cell">{{ doc.updatedAt }}</td>
              <td class="action-cell">
                <button class="action-btn preview" @click="handlePreview(doc)">预览切片</button>
                <button class="action-btn delete" @click="handleDelete(doc)">级联下线</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页控制栏 -->
      <div v-if="totalDocs > 0" class="pagination-bar">
        <div class="page-info">
          共 <span class="page-num">{{ totalDocs }}</span> 篇文档 · 当前第 <span class="page-num">{{ currentPage }}</span> / {{ totalPages }} 页
        </div>
        <div class="page-btns">
          <button class="page-btn" :disabled="currentPage <= 1" @click="prevPage">
            上一页
          </button>
          <span class="page-cur">{{ currentPage }}</span>
          <button class="page-btn" :disabled="currentPage >= totalPages" @click="nextPage">
            下一页
          </button>
        </div>
      </div>
    </div>

    <!-- ── 4. 子视图 B：RAG 检索在线调试沙盒 (Playground) ── -->
    <div v-else-if="currentSubTab === 'playground'" class="view-panel playground-panel">
      <div class="playground-control-card">
        <div class="pg-input-row">
          <input
            v-model="pgQuery"
            type="text"
            class="pg-input"
            placeholder="输入学员提问测试真实检索召回（如：Python装饰器原理、Java异常处理、退费规则）…"
            @keyup.enter="handlePgSearch"
          />
          <button class="btn-primary" :disabled="pgSearching" @click="handlePgSearch">
            <span v-if="pgSearching" class="spin">⟳</span>
            {{ pgSearching ? '检索中…' : '执行混合召回' }}
          </button>
        </div>

        <div class="pg-filter-row">
          <div class="filter-item">
            <span class="label">知识点过滤 (可选):</span>
            <input v-model="pgKp" type="text" class="mini-input" placeholder="如：Python 基础" />
          </div>
          <div class="filter-item">
            <span class="label">召回条数 Top-K:</span>
            <select v-model.number="pgTopK" class="mini-select">
              <option :value="3">3 条</option>
              <option :value="5">5 条</option>
              <option :value="8">8 条</option>
              <option :value="10">10 条</option>
            </select>
          </div>
          <div class="filter-item">
            <label class="toggle-label">
              <input v-model="pgRerank" type="checkbox" />
              <span>启用 Cross-Encoder 精排重排 (gte-rerank-v2)</span>
            </label>
          </div>
        </div>
      </div>

      <!-- 召回切片瀑布流卡片 -->
      <div class="retrieval-results">
        <div v-if="pgResults.length === 0 && !pgSearching" class="empty-hint">
          在上方输入测试问题并回车，体验 ES 向量 + BM25 + Rerank 混合召回实效。
        </div>
        <div v-for="(item, idx) in pgResults" :key="idx" class="chunk-card">
          <div class="chunk-header">
            <div class="chunk-title-wrap">
              <span class="chunk-rank">#{{ idx + 1 }}</span>
              <span class="chunk-title">{{ item.title }}</span>
            </div>
            <div class="chunk-meta">
              <span class="kp-badge">{{ item.kp }}</span>
              <span class="source-tag" :title="item.source">出处: {{ item.source }}</span>
            </div>
          </div>
          <div class="chunk-preview-text">
            {{ item.text || '（点击查看完整段落）' }}
          </div>
        </div>
      </div>
    </div>

    <!-- ── 5. 子视图 C：RAG 三元组评测大盘 ── -->
    <div v-else class="view-panel metrics-panel">
      <div class="triad-cards">
        <div class="triad-card">
          <div class="triad-score text-blue">{{ ragMetrics.contextRelevance }}%</div>
          <div class="triad-title">① 上下文相关度 (Context Relevance)</div>
          <p class="triad-desc">召回知识切片与提问语义的匹配度，衡量检索是否存在噪声切片干扰。</p>
        </div>
        <div class="triad-card">
          <div class="triad-score text-green">{{ ragMetrics.faithfulness }}%</div>
          <div class="triad-title">② 事实忠实度 / 抗幻觉 (Faithfulness)</div>
          <p class="triad-desc">大模型生成回答对检索证据的忠实遵循率，彻底规避张冠李戴与胡编乱造。</p>
        </div>
        <div class="triad-card">
          <div class="triad-score text-purple">{{ ragMetrics.answerRelevance }}%</div>
          <div class="triad-title">③ 答案贴合度 (Answer Relevance)</div>
          <p class="triad-desc">生成答案对用户真实学习意图与知识点疑问的解答直接程度。</p>
        </div>
      </div>

      <!-- 检索工程性能指标卡 -->
      <div class="perf-grid">
        <div class="perf-card">
          <h4>🎯 检索高精命中指标</h4>
          <div class="perf-row">
            <span>Top-5 召回命中率 (HitRate@5):</span>
            <b>{{ ragMetrics.hitRateTop5 }}%</b>
          </div>
          <div class="perf-row">
            <span>平均倒数排名 (MRR 首位命中):</span>
            <b>{{ ragMetrics.mrr }}</b>
          </div>
          <div class="perf-row">
            <span>Cross-Encoder 重排位次增益:</span>
            <b class="text-green">{{ ragMetrics.rerankBoost }}</b>
          </div>
        </div>

        <div class="perf-card">
          <h4>⚡ 端到端 RAG 延迟拆解 (Waterfall)</h4>
          <div class="perf-row">
            <span>Query Rewrite 多轮指代消解:</span>
            <span>{{ ragMetrics.latencyBreakdown?.queryRewriteMs }} ms</span>
          </div>
          <div class="perf-row">
            <span>ES 8.x 向量 + BM25 混合检索:</span>
            <span>{{ ragMetrics.latencyBreakdown?.hybridSearchMs }} ms</span>
          </div>
          <div class="perf-row">
            <span>Cross-Encoder 深度重排 (gte-rerank-v2):</span>
            <span>{{ ragMetrics.latencyBreakdown?.crossEncoderRerankMs }} ms</span>
          </div>
          <div class="perf-row total">
            <span>首字响应延迟 (TTFB):</span>
            <b class="text-blue">{{ ragMetrics.latencyBreakdown?.firstTokenTtfbMs }} ms</b>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 6. 拖拽上传解析弹窗 (Upload Modal) ── -->
    <Teleport to="body">
      <div v-if="showUploadModal" class="modal-overlay" @click.self="showUploadModal = false">
        <div class="modal-card">
          <div class="modal-head">
            <h3>📄 上传多格式文档入库</h3>
            <button class="btn-close" @click="showUploadModal = false">✕</button>
          </div>

          <div class="modal-body">
            <!-- 冲突预警横幅（优化点 5） -->
            <div v-if="conflictAlert" class="conflict-alert-box">
              <div class="alert-title">⚠️ 知识冲突与高相似度预警（优化点 5）：</div>
              <p>{{ conflictAlert }}</p>
            </div>

            <!-- 拖拽上传区 -->
            <div
              class="drop-zone"
              :class="{ active: isDragging, 'has-file': uploadFile }"
              @dragover.prevent="isDragging = true"
              @dragleave.prevent="isDragging = false"
              @drop.prevent="handleFileDrop"
              @click="triggerFileInput"
            >
              <input
                ref="fileInputRef"
                type="file"
                accept=".docx,.pdf,.md,.txt"
                style="display: none;"
                @change="handleFileSelect"
              />
              <div v-if="!uploadFile" class="drop-hint">
                <svg viewBox="0 0 24 24" width="32" height="32" fill="none">
                  <path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <div class="drop-text">点击或拖拽文件到此处解析入库</div>
                <div class="drop-sub">支持 Word (.docx)、PDF (.pdf)、Markdown (.md)、纯文本 (.txt)</div>
              </div>
              <div v-else class="file-picked-info">
                <span class="file-icon">📄</span>
                <div class="file-meta">
                  <div class="file-name">{{ uploadFile.name }}</div>
                  <div class="file-size">{{ formatSize(uploadFile.size) }}</div>
                </div>
                <button class="btn-clear-file" @click.stop="uploadFile = null">移除</button>
              </div>
            </div>

            <!-- 业务分类与元数据表单 -->
            <div class="form-grid">
              <div class="form-item">
                <label class="form-label">业务分类（核心隔离维度）*</label>
                <select v-model="uploadCategory" class="form-select">
                  <option value="SKILL">💡 专业技术技能 (SKILL) - 供讲解答疑</option>
                  <option value="COURSE">📚 课程介绍与大纲 (COURSE) - 供推荐规划</option>
                  <option value="POLICY">📜 售后政策与服务 (POLICY) - 供退款规则</option>
                  <option value="CAREER">💼 职业岗位与转型 (CAREER) - 供求职咨询</option>
                </select>
              </div>

              <div class="form-item">
                <label class="form-label">绑定知识点 (kp)</label>
                <input
                  v-model="uploadKp"
                  type="text"
                  class="form-input"
                  placeholder="如：Python 基础、MySQL 索引、售后退款"
                />
              </div>

              <div class="form-item">
                <label class="form-label">文档权威出处 (Source)</label>
                <input
                  v-model="uploadSource"
                  type="text"
                  class="form-input"
                  placeholder="如：《Python 核心实战手册2026》"
                />
              </div>
            </div>
          </div>

          <div class="modal-foot">
            <button class="btn-cancel" @click="showUploadModal = false">取消</button>
            <button
              class="btn-primary"
              :disabled="!uploadFile || uploading"
              @click="handleSubmitUpload"
            >
              <span v-if="uploading" class="spin">⟳</span>
              {{ uploading ? '结构化解析并入库中…' : '确认结构化解析入库' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ── 7. 文档预览与切片抽屉 (Preview Drawer) ── -->
    <Teleport to="body">
      <div v-if="previewDoc" class="drawer-overlay" @click.self="previewDoc = null">
        <div class="drawer-card">
          <div class="drawer-head">
            <div class="drawer-title-wrap">
              <h3>{{ previewDoc.filename }}</h3>
              <span class="cat-tag" :class="previewDoc.category?.toLowerCase()">
                {{ previewDoc.category }}
              </span>
            </div>
            <button class="btn-close" @click="previewDoc = null">✕</button>
          </div>

          <div class="drawer-body">
            <div class="preview-tabs">
              <button
                class="ptab-btn"
                :class="{ active: previewTab === 'chunks' }"
                @click="previewTab = 'chunks'"
              >
                结构化切片与面包屑列表 ({{ previewChunks.length }} 块)
              </button>
              <button
                class="ptab-btn"
                :class="{ active: previewTab === 'markdown' }"
                @click="previewTab = 'markdown'"
              >
                标准化 Markdown 源码预览
              </button>
            </div>

            <!-- 加载中态 -->
            <div v-if="loadingPreview" class="drawer-loading">
              <span class="spin">⟳</span> 正在读取文档切片与结构化上下文…
            </div>

            <div v-else-if="previewTab === 'chunks'" class="chunks-list">
              <div v-if="previewChunks.length === 0" class="empty-chunks-hint">
                未切分出二级标题切片，点击上方【标准化 Markdown 源码预览】查看文档全文。
              </div>
              <div v-for="(c, i) in previewChunks" :key="i" class="pchunk-item">
                <div class="pchunk-head">
                  <span class="pchunk-badge">#{{ i + 1 }}</span>
                  <b class="pchunk-title">{{ c.title }}</b>
                  <span class="pchunk-tokens">约 {{ c.tokenCount }} Tokens</span>
                </div>
                <div class="pchunk-bc">{{ c.breadcrumb }}</div>
                <div class="pchunk-body">{{ c.text }}</div>
              </div>
            </div>

            <div v-else class="md-source-view">
              <pre><code>{{ previewContent }}</code></pre>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  fetchKnowledgeOverview,
  fetchKnowledgeDocs,
  fetchKnowledgeDetail,
  uploadKnowledgeDoc,
  deleteKnowledgeDoc,
  fetchRagMetrics,
  searchRetrieval,
  syncAllCorpus,
} from '../adminApi'

const overview = ref({})
const currentSubTab = ref('docs')
const syncing = ref(false)

// 业务分类
const activeCategory = ref('ALL')
const categoryOptions = [
  { key: 'ALL', label: '全部资产' },
  { key: 'SKILL', label: '💡 技术技能' },
  { key: 'COURSE', label: '📚 课程大纲' },
  { key: 'POLICY', label: '📜 售后政策' },
  { key: 'CAREER', label: '💼 职业画像' },
]

// 文档列表状态与分页
const docList = ref([])
const totalDocs = ref(0)
const currentPage = ref(1)
const pageSize = ref(15)
const keyword = ref('')
const loadingDocs = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(totalDocs.value / pageSize.value)))

// 上传弹窗状态
const showUploadModal = ref(false)
const uploadFile = ref(null)
const uploadCategory = ref('SKILL')
const uploadKp = ref('')
const uploadSource = ref('')
const uploading = ref(false)
const isDragging = ref(false)
const fileInputRef = ref(null)
const conflictAlert = ref('')

// 预览抽屉状态
const previewDoc = ref(null)
const previewTab = ref('chunks')
const previewContent = ref('')
const previewChunks = ref([])
const loadingPreview = ref(false)

// Playground 状态
const pgQuery = ref('')
const pgKp = ref('')
const pgTopK = ref(5)
const pgRerank = ref(true)
const pgSearching = ref(false)
const pgResults = ref([])

// RAG 评测指标状态
const ragMetrics = ref({})

async function loadOverview() {
  try {
    overview.value = await fetchKnowledgeOverview()
  } catch (e) {
    console.error('加载知识库概览失败', e)
  }
}

async function loadDocs() {
  loadingDocs.value = true
  try {
    const res = await fetchKnowledgeDocs({
      category: activeCategory.value,
      keyword: keyword.value,
      page: currentPage.value,
      size: pageSize.value,
    })
    docList.value = res.list || []
    totalDocs.value = res.total || 0
  } catch (e) {
    console.error('加载文档列表失败', e)
    alert('加载知识库列表失败: ' + e.message)
  } finally {
    loadingDocs.value = false
  }
}

async function loadRagMetrics() {
  try {
    ragMetrics.value = await fetchRagMetrics()
  } catch (e) {
    console.error('加载 RAG 指标失败', e)
  }
}

function setCategory(cat) {
  activeCategory.value = cat
  currentPage.value = 1
  keyword.value = ''
  loadDocs()
}

function prevPage() {
  if (currentPage.value > 1) {
    currentPage.value--
    loadDocs()
  }
}

function nextPage() {
  if (currentPage.value < totalPages.value) {
    currentPage.value++
    loadDocs()
  }
}

function getCategoryCount(cat) {
  if (cat === 'ALL') return overview.value.totalDocuments || 0
  return overview.value.categoryCounts?.[cat] || 0
}

function categoryLabel(cat) {
  const map = { SKILL: '技术技能', COURSE: '课程大纲', POLICY: '售后政策', CAREER: '岗位画像' }
  return map[cat] || cat
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// 同步所有语料
async function handleSyncAll() {
  if (syncing.value) return
  syncing.value = true
  try {
    await syncAllCorpus()
    alert('全量知识库与向量索引同步完成！')
    await loadOverview()
    await loadDocs()
  } catch (e) {
    alert('同步失败: ' + e.message)
  } finally {
    syncing.value = false
  }
}

// 预览文档详情
async function handlePreview(doc) {
  previewDoc.value = doc
  previewTab.value = 'chunks'
  previewContent.value = ''
  previewChunks.value = []
  loadingPreview.value = true
  try {
    const detail = await fetchKnowledgeDetail(doc.docId, doc.category)
    previewContent.value = detail.content || ''
    previewChunks.value = detail.chunks || []
  } catch (e) {
    alert('获取文档详情失败: ' + e.message)
  } finally {
    loadingPreview.value = false
  }
}

// 级联删除文档
async function handleDelete(doc) {
  if (!confirm(`确定要级联下线文档《${doc.filename}》吗？\n下线后将自动从 ES 向量索引中物理删除所有相关切片。`)) {
    return
  }
  try {
    await deleteKnowledgeDoc(doc.docId, doc.category)
    alert('文档及向量切片已安全级联下线！')
    await loadOverview()
    await loadDocs()
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
}

// 上传交互
function triggerFileInput() {
  fileInputRef.value?.click()
}

function handleFileSelect(e) {
  const f = e.target.files?.[0]
  if (f) uploadFile.value = f
}

function handleFileDrop(e) {
  isDragging.value = false
  const f = e.dataTransfer.files?.[0]
  if (f) uploadFile.value = f
}

async function handleSubmitUpload() {
  if (!uploadFile.value || uploading.value) return
  uploading.value = true
  conflictAlert.value = ''
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    fd.append('category', uploadCategory.value)
    fd.append('kp', uploadKp.value)
    fd.append('source', uploadSource.value)

    const res = await uploadKnowledgeDoc(fd)
    if (res.conflictWarnings && res.conflictWarnings.length > 0) {
      conflictAlert.value = res.conflictWarnings.map(w => w.alertMessage).join('\n')
      alert('文档已成功入库！⚠️ 但检测到潜在内容重叠冲突，详情见弹窗警报。')
    } else {
      alert('文档已成功完成结构化解析并向量化写入 Elasticsearch！')
      showUploadModal.value = false
      uploadFile.value = null
      uploadKp.value = ''
      uploadSource.value = ''
    }
    await loadOverview()
    await loadDocs()
  } catch (e) {
    alert('上传解析失败: ' + e.message)
  } finally {
    uploading.value = false
  }
}

// Playground 调试检索
async function handlePgSearch() {
  if (!pgQuery.value.trim() || pgSearching.value) return
  pgSearching.value = true
  try {
    pgResults.value = await searchRetrieval({
      query: pgQuery.value.trim(),
      kp: pgKp.value.trim(),
      topK: pgTopK.value,
      rerank: pgRerank.value,
    })
  } catch (e) {
    alert('检索调试失败: ' + e.message)
  } finally {
    pgSearching.value = false
  }
}

onMounted(() => {
  loadOverview()
  loadDocs()
  loadRagMetrics()
})
</script>

<style scoped>
.knowledge-root {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}

/* 顶部大盘卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  padding: 16px 20px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.02);
}

.stat-icon-wrap {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-icon-wrap.blue { background: rgba(9, 105, 218, 0.1); color: #0969da; }
.stat-icon-wrap.purple { background: rgba(130, 80, 223, 0.1); color: #8250df; }
.stat-icon-wrap.green { background: rgba(26, 127, 55, 0.1); color: #1a7f37; }
.stat-icon-wrap.orange { background: rgba(217, 119, 6, 0.1); color: #d97706; }

.stat-val { font-size: 20px; font-weight: 700; color: #1f2328; }
.stat-val .unit { font-size: 13px; font-weight: 400; color: #656d76; }
.stat-val.text-green { color: #1a7f37; }
.stat-val.text-orange { color: #d97706; }
.stat-label { font-size: 12px; color: #656d76; margin-top: 2px; }

/* 子导航栏 */
.sub-nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
  padding: 8px 14px;
}

.sub-tabs {
  display: flex;
  gap: 8px;
}

.sub-tab-btn {
  background: none;
  border: none;
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #656d76;
  cursor: pointer;
  transition: all 0.15s ease;
}

.sub-tab-btn:hover { color: #1f2328; background: rgba(0, 0, 0, 0.04); }
.sub-tab-btn.active { color: #0969da; background: rgba(9, 105, 218, 0.08); }

.sub-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-sync {
  background: #fff;
  border: 1px solid #d0d7de;
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #1f2328;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}
.btn-sync:hover { background: #f6f8fa; border-color: #0969da; color: #0969da; }

.btn-upload {
  background: #0969da;
  border: none;
  border-radius: 8px;
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-upload:hover { background: #0858b9; box-shadow: 0 2px 6px rgba(9, 105, 218, 0.3); }

/* 视图面板容器 */
.view-panel {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  padding: 20px;
}

/* 过滤栏 */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.category-pills {
  display: flex;
  gap: 8px;
}

.pill-btn {
  background: #f6f8fa;
  border: 1px solid #d0d7de;
  border-radius: 20px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  color: #656d76;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.15s ease;
}
.pill-btn:hover { border-color: #0969da; color: #0969da; }
.pill-btn.active { background: #0969da; border-color: #0969da; color: #fff; }
.pill-badge {
  background: rgba(0, 0, 0, 0.08);
  border-radius: 10px;
  padding: 1px 6px;
  font-size: 11px;
}
.pill-btn.active .pill-badge { background: rgba(255, 255, 255, 0.25); color: #fff; }

.search-wrap {
  display: flex;
  gap: 8px;
}
.search-input {
  width: 280px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  outline: none;
}
.search-input:focus { border-color: #0969da; box-shadow: 0 0 0 3px rgba(9, 105, 218, 0.15); }
.btn-search {
  background: #f6f8fa;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 12px;
  cursor: pointer;
}

/* 表格样式 */
.table-container { overflow-x: auto; }
.doc-table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
.doc-table th {
  padding: 10px 14px;
  border-bottom: 1px solid #d0d7de;
  color: #656d76;
  font-weight: 600;
  background: #f6f8fa;
}
.doc-table td { padding: 12px 14px; border-bottom: 1px solid #eaeef2; color: #1f2328; }
.doc-row:hover { background: rgba(0, 0, 0, 0.015); }

.name-cell { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.cat-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
}
.cat-tag.skill { background: rgba(9, 105, 218, 0.1); color: #0969da; }
.cat-tag.course { background: rgba(130, 80, 223, 0.1); color: #8250df; }
.cat-tag.policy { background: rgba(217, 119, 6, 0.1); color: #d97706; }
.cat-tag.career { background: rgba(26, 127, 55, 0.1); color: #1a7f37; }

.kp-badge { background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 4px; padding: 2px 6px; font-size: 11px; color: #57606a; }
.chunk-badge { font-weight: 600; color: #0969da; }
.size-cell, .time-cell { font-size: 12px; color: #656d76; }

.action-cell { text-align: right; }
.action-btn {
  background: none;
  border: none;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  border-radius: 4px;
}
.action-btn.preview { color: #0969da; }
.action-btn.preview:hover { background: rgba(9, 105, 218, 0.08); }
.action-btn.delete { color: #cf222e; }
.action-btn.delete:hover { background: rgba(207, 34, 46, 0.08); }

/* Playground 调试器 */
.playground-control-card {
  background: #f6f8fa;
  border: 1px solid #d0d7de;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 20px;
}
.pg-input-row { display: flex; gap: 10px; margin-bottom: 12px; }
.pg-input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #d0d7de;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}
.pg-input:focus { border-color: #0969da; box-shadow: 0 0 0 3px rgba(9, 105, 218, 0.15); }
.pg-filter-row { display: flex; align-items: center; gap: 24px; font-size: 13px; color: #57606a; }
.mini-input, .mini-select {
  border: 1px solid #d0d7de;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
  outline: none;
}
.toggle-label { display: flex; align-items: center; gap: 6px; cursor: pointer; }

.retrieval-results { display: flex; flex-direction: column; gap: 12px; }
.chunk-card {
  border: 1px solid #d0d7de;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
  transition: all 0.15s ease;
}
.chunk-card:hover { border-color: #0969da; box-shadow: 0 2px 6px rgba(0,0,0,0.04); }
.chunk-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.chunk-rank { font-weight: 700; color: #0969da; margin-right: 8px; }
.chunk-title { font-weight: 600; font-size: 14px; color: #1f2328; }
.chunk-preview-text { font-size: 13px; line-height: 1.6; color: #57606a; white-space: pre-wrap; }

/* RAG 评测大盘 */
.triad-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 20px; }
.triad-card {
  background: #fff;
  border: 1px solid #d0d7de;
  border-radius: 10px;
  padding: 20px;
}
.triad-score { font-size: 32px; font-weight: 800; margin-bottom: 6px; }
.triad-score.text-blue { color: #0969da; }
.triad-score.text-green { color: #1a7f37; }
.triad-score.text-purple { color: #8250df; }
.triad-title { font-size: 14px; font-weight: 700; color: #1f2328; margin-bottom: 6px; }
.triad-desc { font-size: 12px; color: #656d76; line-height: 1.5; }

.perf-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.perf-card { background: #fff; border: 1px solid #d0d7de; border-radius: 10px; padding: 18px; }
.perf-card h4 { margin: 0 0 14px 0; font-size: 14px; color: #1f2328; }
.perf-row { display: flex; justify-content: space-between; font-size: 13px; color: #57606a; padding: 8px 0; border-bottom: 1px dashed #eaeef2; }
.perf-row.total { border-bottom: none; font-weight: 700; padding-top: 12px; color: #1f2328; }

/* 弹窗与抽屉 */
.modal-overlay, .drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal-card {
  background: #fff;
  border-radius: 12px;
  width: 580px;
  max-width: 90vw;
  box-shadow: 0 8px 24px rgba(0,0,0,0.15);
  overflow: hidden;
}
.modal-head { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #eaeef2; }
.modal-head h3 { margin: 0; font-size: 16px; }
.btn-close { background: none; border: none; font-size: 16px; cursor: pointer; color: #656d76; }
.modal-body { padding: 20px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; border-top: 1px solid #eaeef2; background: #f6f8fa; }

.drop-zone {
  border: 2px dashed #d0d7de;
  border-radius: 8px;
  padding: 24px;
  text-align: center;
  cursor: pointer;
  background: #f6f8fa;
  margin-bottom: 16px;
  transition: all 0.15s ease;
}
.drop-zone.active, .drop-zone:hover { border-color: #0969da; background: rgba(9, 105, 218, 0.04); }
.drop-text { font-size: 14px; font-weight: 600; color: #1f2328; margin-top: 8px; }
.drop-sub { font-size: 12px; color: #656d76; margin-top: 4px; }
.file-picked-info { display: flex; align-items: center; gap: 12px; }

.form-grid { display: flex; flex-direction: column; gap: 12px; }
.form-item { display: flex; flex-direction: column; gap: 4px; }
.form-label { font-size: 12px; font-weight: 600; color: #57606a; }
.form-input, .form-select { border: 1px solid #d0d7de; border-radius: 6px; padding: 7px 10px; font-size: 13px; outline: none; }
.form-input:focus, .form-select:focus { border-color: #0969da; }

.conflict-alert-box {
  background: #fff8c5;
  border: 1px solid #d4a72c;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  color: #7d4e00;
  margin-bottom: 14px;
}

/* 抽屉样式 */
.drawer-card {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 680px;
  max-width: 90vw;
  background: #fff;
  box-shadow: -4px 0 20px rgba(0,0,0,0.1);
  display: flex;
  flex-direction: column;
}
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #eaeef2; }
.drawer-body { flex: 1; overflow-y: auto; padding: 20px; }
.preview-tabs { display: flex; gap: 8px; margin-bottom: 16px; border-bottom: 1px solid #d0d7de; padding-bottom: 8px; }
.ptab-btn { background: none; border: none; padding: 6px 12px; font-size: 13px; font-weight: 600; color: #656d76; cursor: pointer; border-radius: 6px; }
.ptab-btn.active { color: #0969da; background: rgba(9, 105, 218, 0.08); }

.pchunk-item { border: 1px solid #d0d7de; border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
.pchunk-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.pchunk-badge { font-weight: 700; color: #0969da; }
.pchunk-tokens { font-size: 11px; color: #656d76; margin-left: auto; }
.pchunk-bc { font-size: 12px; font-weight: 600; color: #8250df; margin-bottom: 6px; }
.pchunk-body { font-size: 13px; color: #57606a; line-height: 1.5; white-space: pre-wrap; }

.name-text-group { display: flex; flex-direction: column; gap: 2px; }
.doc-source-sub { font-size: 11px; color: #8c959f; font-weight: 400; }

/* 分页栏 */
.pagination-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-top: 1px solid #d0d7de;
  background: #f6f8fa;
  border-radius: 0 0 8px 8px;
  font-size: 12px;
  color: #656d76;
}
.page-num { font-weight: 700; color: #1f2328; }
.page-btns { display: flex; align-items: center; gap: 8px; }
.page-btn {
  background: #fff;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  color: #1f2328;
  transition: all 0.15s ease;
}
.page-btn:hover:not(:disabled) { border-color: #0969da; color: #0969da; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.page-cur {
  display: inline-block;
  min-width: 24px;
  height: 24px;
  line-height: 24px;
  text-align: center;
  background: #0969da;
  color: #fff;
  border-radius: 6px;
  font-weight: 600;
  font-size: 11px;
}

/* 抽屉加载与空态 */
.drawer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 60px 20px;
  font-size: 14px;
  color: #656d76;
}
.empty-chunks-hint {
  padding: 30px 20px;
  text-align: center;
  background: #f6f8fa;
  border: 1px dashed #d0d7de;
  border-radius: 8px;
  color: #656d76;
  font-size: 13px;
  margin-bottom: 16px;
}

.spin { display: inline-block; animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
