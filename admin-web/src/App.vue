<script setup lang="ts">
import { onMounted, ref } from 'vue';
import {
  adminApi,
  clearToken,
  getSavedToken,
  saveToken,
  type AskLogItem,
  type AuditEventItem,
  type DepartmentItem,
  type DocumentItem,
  type IndexTaskItem,
  type KnowledgeBaseItem,
  type RetrievalDiagnostics,
  type UserItem,
} from './api/client';

type TabKey = 'review' | 'docs' | 'org' | 'audit';

const DEFAULT_KB_ID = '00000000-0000-0000-0000-000000000101';

const token = ref(getSavedToken());
const username = ref('demo');
const password = ref('');
const loginError = ref('');
const loading = ref(false);
const errorMsg = ref('');
const activeTab = ref<TabKey>('review');

// Review / HITL state
const needsReviewOnly = ref(true);
const filterStatus = ref('');
const filterRating = ref('');
const askLogs = ref<AskLogItem[]>([]);
const selectedDiagnostics = ref<RetrievalDiagnostics | null>(null);

// Docs & Index tasks state
const documents = ref<DocumentItem[]>([]);
const indexTasks = ref<IndexTaskItem[]>([]);
const selectedKbId = ref(DEFAULT_KB_ID);

// Org state
const knowledgeBases = ref<KnowledgeBaseItem[]>([]);
const departments = ref<DepartmentItem[]>([]);
const users = ref<UserItem[]>([]);
const newDeptName = ref('');

// Audit state
const auditOutcome = ref('');
const auditEvents = ref<AuditEventItem[]>([]);

async function handleLogin() {
  loginError.value = '';
  loading.value = true;
  try {
    const res = await adminApi.login(username.value.trim(), password.value);
    saveToken(res.token);
    token.value = res.token;
    await loadCurrentTab();
  } catch (err) {
    loginError.value = err instanceof Error ? err.message : '登录失败';
  } finally {
    loading.value = false;
  }
}

function handleLogout() {
  clearToken();
  token.value = '';
  selectedDiagnostics.value = null;
}

async function switchTab(tab: TabKey) {
  activeTab.value = tab;
  await loadCurrentTab();
}

async function loadCurrentTab() {
  if (!token.value) return;
  errorMsg.value = '';
  loading.value = true;
  try {
    if (activeTab.value === 'review') {
      const res = await adminApi.listAskLogs({
        needsReview: needsReviewOnly.value,
        resultStatus: filterStatus.value || undefined,
        rating: filterRating.value || undefined,
      });
      askLogs.value = res.items;
    } else if (activeTab.value === 'docs') {
      const [docsRes, tasksRes] = await Promise.all([
        adminApi.listDocuments(),
        adminApi.listIndexTasks(selectedKbId.value),
      ]);
      documents.value = docsRes.items;
      indexTasks.value = tasksRes.items;
    } else if (activeTab.value === 'org') {
      const [kbRes, deptRes, userRes] = await Promise.all([
        adminApi.listKnowledgeBases(),
        adminApi.listDepartments(),
        adminApi.listUsers(),
      ]);
      knowledgeBases.value = kbRes.items;
      departments.value = deptRes.items;
      users.value = userRes.items;
    } else if (activeTab.value === 'audit') {
      const res = await adminApi.listAuditEvents(auditOutcome.value || undefined);
      auditEvents.value = res.items;
    }
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '加载失败';
    if (!getSavedToken()) {
      token.value = '';
    }
  } finally {
    loading.value = false;
  }
}

async function inspectDiagnostics(requestId: string) {
  errorMsg.value = '';
  try {
    selectedDiagnostics.value = await adminApi.getRetrievalDiagnostics(requestId);
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '获取检索诊断失败';
  }
}

async function handleFileUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  errorMsg.value = '';
  loading.value = true;
  try {
    await adminApi.uploadDocument(file);
    await loadCurrentTab();
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '上传失败';
  } finally {
    input.value = '';
    loading.value = false;
  }
}

async function runDocAction(action: () => Promise<unknown>) {
  errorMsg.value = '';
  try {
    await action();
    await loadCurrentTab();
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '操作失败';
  }
}

async function handleCreateDepartment() {
  const name = newDeptName.value.trim();
  if (!name) return;
  await runDocAction(async () => {
    await adminApi.createDepartment(name);
    newDeptName.value = '';
  });
}

onMounted(() => {
  if (token.value) {
    void loadCurrentTab();
  }
});
</script>

<template>
  <div v-if="!token" class="login-wrap">
    <form class="card login-card" @submit.prevent="handleLogin">
      <h2 style="margin-top: 0">Enterprise RAG 运营台</h2>
      <p style="color: var(--muted); margin-bottom: 16px">
        使用 SYSTEM_ADMIN 或 AUDITOR 账号登录
      </p>
      <div v-if="loginError" class="error-banner">{{ loginError }}</div>
      <div style="display: grid; gap: 12px">
        <input v-model="username" class="input" placeholder="用户名" required />
        <input
          v-model="password"
          class="input"
          type="password"
          placeholder="密码"
          required
        />
        <button class="btn primary" type="submit" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </div>
    </form>
  </div>

  <div v-else class="layout">
    <aside class="sidebar">
      <div class="brand">RAG 运营与复核台</div>
      <button
        class="nav-btn"
        :class="{ active: activeTab === 'review' }"
        @click="switchTab('review')"
      >
        拒答与差评复核
      </button>
      <button
        class="nav-btn"
        :class="{ active: activeTab === 'docs' }"
        @click="switchTab('docs')"
      >
        文档与索引任务
      </button>
      <button
        class="nav-btn"
        :class="{ active: activeTab === 'org' }"
        @click="switchTab('org')"
      >
        组织与知识库
      </button>
      <button
        class="nav-btn"
        :class="{ active: activeTab === 'audit' }"
        @click="switchTab('audit')"
      >
        安全审计日志
      </button>
      <div style="margin-top: auto">
        <button class="nav-btn" style="width: 100%" @click="handleLogout">
          退出登录
        </button>
      </div>
    </aside>

    <main class="main">
      <div v-if="errorMsg" class="error-banner">{{ errorMsg }}</div>

      <section v-if="activeTab === 'review'">
        <div class="toolbar">
          <h2 style="margin: 0 12px 0 0">拒答与差评复核池 (Human-in-the-Loop)</h2>
          <label style="display: flex; align-items: center; gap: 6px">
            <input v-model="needsReviewOnly" type="checkbox" @change="loadCurrentTab" />
            仅看需复核（拒答 / 失败 / 点踩）
          </label>
          <select v-model="filterStatus" class="select" @change="loadCurrentTab">
            <option value="">全部状态</option>
            <option value="ANSWERED">ANSWERED</option>
            <option value="NOT_FOUND">NOT_FOUND</option>
            <option value="FAILED">FAILED</option>
          </select>
          <select v-model="filterRating" class="select" @change="loadCurrentTab">
            <option value="">全部反馈</option>
            <option value="NOT_HELPFUL">NOT_HELPFUL</option>
            <option value="HELPFUL">HELPFUL</option>
          </select>
          <button class="btn" @click="loadCurrentTab">刷新</button>
          <button class="btn primary" @click="switchTab('docs')">去补充文档</button>
        </div>

        <table>
          <thead>
            <tr>
              <th>Request ID</th>
              <th>状态 / 失败原因</th>
              <th>用户反馈</th>
              <th>耗时 / Token</th>
              <th>模型 / 模式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in askLogs" :key="item.requestId">
              <td style="font-family: monospace; font-size: 12px">{{ item.requestId }}</td>
              <td>
                <span
                  class="badge"
                  :class="item.resultStatus === 'ANSWERED' ? 'ok' : 'warn'"
                >
                  {{ item.resultStatus }}
                </span>
                <div v-if="item.failureReason" style="color: var(--muted); font-size: 12px">
                  {{ item.failureReason }}
                </div>
              </td>
              <td>
                <span
                  v-if="item.feedbackRating"
                  class="badge"
                  :class="item.feedbackRating === 'HELPFUL' ? 'ok' : 'danger'"
                >
                  {{ item.feedbackRating }}
                </span>
                <span v-else style="color: var(--muted)">-</span>
                <div v-if="item.feedbackReason" style="font-size: 12px">
                  {{ item.feedbackReason }}
                </div>
              </td>
              <td>{{ item.latencyMs }} ms / {{ item.tokenUsage }} tok</td>
              <td>{{ item.modelId || '-' }} ({{ item.retrievalMode }})</td>
              <td>
                <button class="btn" @click="inspectDiagnostics(item.requestId)">
                  检索诊断
                </button>
              </td>
            </tr>
            <tr v-if="!askLogs.length">
              <td colspan="6" style="color: var(--muted); text-align: center">
                暂无匹配的问答记录
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="selectedDiagnostics" class="card" style="margin-top: 16px">
          <div class="toolbar" style="justify-content: space-between; margin-bottom: 8px">
            <strong>检索诊断 · {{ selectedDiagnostics.requestId }}</strong>
            <button class="btn" @click="selectedDiagnostics = null">关闭</button>
          </div>
          <p style="color: var(--muted); margin-top: 0">
            阈值: {{ selectedDiagnostics.similarityThreshold ?? '-' }} · Top-K:
            {{ selectedDiagnostics.topK ?? '-' }} · 最高相似度:
            {{ selectedDiagnostics.topSimilarity?.toFixed(4) ?? '-' }}
          </p>
          <table>
            <thead>
              <tr>
                <th>Rank</th>
                <th>文档</th>
                <th>定位</th>
                <th>相似度</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="hit in selectedDiagnostics.hits" :key="hit.chunkId">
                <td>#{{ hit.rank }}</td>
                <td>{{ hit.filename }}</td>
                <td>{{ hit.locator }}</td>
                <td>{{ hit.similarity.toFixed(4) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else-if="activeTab === 'docs'">
        <div class="toolbar">
          <h2 style="margin: 0 12px 0 0">文档与索引任务</h2>
          <label class="btn primary">
            上传补充文档
            <input type="file" style="display: none" @change="handleFileUpload" />
          </label>
          <button class="btn" @click="loadCurrentTab">刷新</button>
        </div>

        <div class="grid-2">
          <div>
            <h3>文档列表 ({{ documents.length }})</h3>
            <table>
              <thead>
                <tr>
                  <th>文件名</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="doc in documents" :key="doc.id">
                  <td>{{ doc.filename }}</td>
                  <td>{{ doc.disabled ? 'DISABLED' : doc.status }}</td>
                  <td style="display: flex; gap: 6px">
                    <button
                      class="btn"
                      @click="runDocAction(() => adminApi.reindexDocument(doc.id))"
                    >
                      重建索引
                    </button>
                    <button
                      class="btn"
                      @click="
                        runDocAction(() =>
                          doc.disabled
                            ? adminApi.enableDocument(doc.id)
                            : adminApi.disableDocument(doc.id)
                        )
                      "
                    >
                      {{ doc.disabled ? '启用' : '停用' }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div>
            <h3>索引任务 ({{ indexTasks.length }})</h3>
            <table>
              <thead>
                <tr>
                  <th>文档 ID</th>
                  <th>状态 / 重试</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="task in indexTasks" :key="task.id">
                  <td style="font-family: monospace; font-size: 12px">
                    {{ task.documentId }}
                  </td>
                  <td>
                    {{ task.status }} ({{ task.attempts }}/{{ task.maxAttempts }})
                    <div v-if="task.errorMessage" style="color: var(--danger); font-size: 12px">
                      {{ task.errorMessage }}
                    </div>
                  </td>
                  <td>
                    <button
                      v-if="task.status === 'FAILED'"
                      class="btn"
                      @click="
                        runDocAction(() =>
                          adminApi.retryIndexTask(selectedKbId, task.id)
                        )
                      "
                    >
                      人工重试
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section v-else-if="activeTab === 'org'">
        <div class="toolbar">
          <h2 style="margin: 0 12px 0 0">组织与知识库</h2>
          <input
            v-model="newDeptName"
            class="input"
            placeholder="新部门名称"
          />
          <button class="btn primary" @click="handleCreateDepartment">创建部门</button>
          <button class="btn" @click="loadCurrentTab">刷新</button>
        </div>

        <div class="grid-2">
          <div class="card">
            <h3 style="margin-top: 0">知识库 ({{ knowledgeBases.length }})</h3>
            <ul>
              <li v-for="kb in knowledgeBases" :key="kb.id">
                {{ kb.name }} · {{ kb.status }} ({{ kb.id }})
              </li>
            </ul>
            <h3>部门 ({{ departments.length }})</h3>
            <ul>
              <li v-for="dept in departments" :key="dept.id">{{ dept.name }}</li>
            </ul>
          </div>
          <div class="card">
            <h3 style="margin-top: 0">用户 ({{ users.length }})</h3>
            <ul>
              <li v-for="u in users" :key="u.id">
                {{ u.username }} · {{ u.status }}
              </li>
            </ul>
          </div>
        </div>
      </section>

      <section v-else-if="activeTab === 'audit'">
        <div class="toolbar">
          <h2 style="margin: 0 12px 0 0">安全审计日志</h2>
          <select v-model="auditOutcome" class="select" @change="loadCurrentTab">
            <option value="">全部结果</option>
            <option value="ALLOW">ALLOW</option>
            <option value="DENY">DENY</option>
            <option value="ERROR">ERROR</option>
          </select>
          <button class="btn" @click="loadCurrentTab">刷新</button>
        </div>

        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>动作</th>
              <th>资源</th>
              <th>结果</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ev in auditEvents" :key="ev.id">
              <td>{{ ev.createdAt }}</td>
              <td>{{ ev.action }}</td>
              <td>{{ ev.resourceType }} {{ ev.resourceId || '' }}</td>
              <td>
                <span
                  class="badge"
                  :class="ev.outcome === 'ALLOW' ? 'ok' : 'danger'"
                >
                  {{ ev.outcome }}
                </span>
              </td>
              <td>{{ ev.reason || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </main>
  </div>
</template>
