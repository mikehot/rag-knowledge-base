export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface AskLogItem {
  requestId: string;
  userId: string;
  resultStatus: string;
  failureReason: string | null;
  found: boolean;
  tokenUsage: number;
  latencyMs: number;
  modelId: string | null;
  retrievalMode: string;
  feedbackRating: string | null;
  feedbackReason: string | null;
  createdAt: string | null;
}

export interface RetrievalHit {
  rank: number;
  chunkId: string;
  documentId: string;
  filename: string;
  locator: string;
  similarity: number;
}

export interface RetrievalDiagnostics {
  requestId: string;
  createdAt: string;
  resultStatus: string;
  failureReason: string | null;
  found: boolean;
  retrievalMode: string;
  candidateCount: number;
  topSimilarity: number | null;
  topK: number | null;
  similarityThreshold: number | null;
  hits: RetrievalHit[];
}

export interface DocumentItem {
  id: string;
  filename: string;
  status: string;
  disabled?: boolean;
  chunkCount?: number;
  createdAt?: string;
}

export interface IndexTaskItem {
  id: string;
  documentId: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  errorMessage?: string | null;
  updatedAt?: string;
}

export interface KnowledgeBaseItem {
  id: string;
  name: string;
  status: string;
}

export interface DepartmentItem {
  id: string;
  name: string;
}

export interface UserItem {
  id: string;
  username: string;
  status: string;
  roles?: string[];
}

export interface AuditEventItem {
  id: string;
  userId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: string;
  reason: string | null;
  createdAt: string;
}

const TOKEN_KEY = 'rag_admin_token';

export function getSavedToken(): string {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function saveToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getSavedToken();
  const headers = new Headers(init.headers || {});
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const resp = await fetch(path, { ...init, headers });
  const text = await resp.text();
  let json: ApiEnvelope<T>;
  try {
    json = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`服务响应异常 (HTTP ${resp.status})`);
  }
  if (!resp.ok || json.code !== 0) {
    if (resp.status === 401) {
      clearToken();
    }
    throw new Error(json.message || `请求失败 (HTTP ${resp.status})`);
  }
  return json.data;
}

export const adminApi = {
  login(username: string, password: string) {
    return request<{ token: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  },
  listAskLogs(params: { needsReview: boolean; resultStatus?: string; rating?: string }) {
    const query = new URLSearchParams({
      needsReview: String(params.needsReview),
      limit: '100',
    });
    if (params.resultStatus) query.set('resultStatus', params.resultStatus);
    if (params.rating) query.set('rating', params.rating);
    return request<{ items: AskLogItem[] }>(`/api/admin/ask-logs?${query}`);
  },
  getRetrievalDiagnostics(requestId: string) {
    return request<RetrievalDiagnostics>(`/api/admin/retrieval-diagnostics/${requestId}`);
  },
  listDocuments() {
    return request<{ items: DocumentItem[] }>('/api/documents');
  },
  uploadDocument(file: File) {
    const form = new FormData();
    form.append('file', file);
    return request<unknown>('/api/documents/upload', {
      method: 'POST',
      body: form,
    });
  },
  reindexDocument(id: string) {
    return request<unknown>(`/api/documents/${id}/reindex`, { method: 'POST' });
  },
  disableDocument(id: string) {
    return request<unknown>(`/api/documents/${id}/disable`, { method: 'POST' });
  },
  enableDocument(id: string) {
    return request<unknown>(`/api/documents/${id}/enable`, { method: 'POST' });
  },
  listIndexTasks(kbId: string) {
    return request<{ items: IndexTaskItem[] }>(
      `/api/admin/knowledge-bases/${kbId}/index-tasks?limit=50`
    );
  },
  retryIndexTask(kbId: string, taskId: string) {
    return request<unknown>(`/api/admin/knowledge-bases/${kbId}/index-tasks/${taskId}/retry`, {
      method: 'POST',
    });
  },
  listKnowledgeBases() {
    return request<{ items: KnowledgeBaseItem[] }>('/api/admin/knowledge-bases');
  },
  listDepartments() {
    return request<{ items: DepartmentItem[] }>('/api/admin/departments');
  },
  createDepartment(name: string) {
    return request<DepartmentItem>('/api/admin/departments', {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
  },
  listUsers() {
    return request<{ items: UserItem[] }>('/api/admin/users');
  },
  listAuditEvents(outcome?: string) {
    const query = new URLSearchParams({ limit: '100' });
    if (outcome) query.set('outcome', outcome);
    return request<{ items: AuditEventItem[] }>(`/api/admin/audit-events?${query}`);
  },
};
