class ApiConfig {
  static const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );

  static const username = String.fromEnvironment(
    'APP_USERNAME',
    defaultValue: 'demo',
  );

  static const password = String.fromEnvironment(
    'APP_PASSWORD',
    defaultValue: 'demo123456',
  );
}
