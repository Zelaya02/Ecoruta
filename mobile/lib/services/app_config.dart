class AppConfig {
  // Conectado por ADB reverse USB (127.0.0.1 mapeado a la PC)
  static String serverHost = '127.0.0.1';
  static int backendPort = 8085;
  static int agentPort = 5050;

  static String get backendUrl => 'http://$serverHost:$backendPort';
  static String get agentUrl => 'http://$serverHost:$agentPort';

  static String get loginUrl => '$backendUrl/api/login';
  static String get registroUrl => '$backendUrl/api/registro';
  static String get validarYEnviarUrl => '$agentUrl/api/denuncias/validar-y-enviar';
  static String rastreoUrl(String ticket) => '$agentUrl/api/denuncias/rastreo/$ticket';
}
