-- Local integration database; permissions are limited to this project's databases.
CREATE DATABASE IF NOT EXISTS servicehub_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON servicehub_test.* TO 'servicehub'@'%';
