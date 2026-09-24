String indexTaskStatusLabel(String status, {String? errorMessage}) {
  if (status == 'PENDING' && errorMessage?.trim().isNotEmpty == true) {
    return '等待重试';
  }
  return switch (status) {
    'SUCCEEDED' => '成功',
    'FAILED' => '失败',
    'RUNNING' => '处理中',
    'PENDING' => '排队中',
    _ => status,
  };
}
