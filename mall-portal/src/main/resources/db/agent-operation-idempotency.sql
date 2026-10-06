-- Durable idempotency for user write operations.
-- Apply to the mall schema before deploying the portal / agent changes.
-- The business write and the COMPLETED result must be committed in one transaction.
CREATE TABLE IF NOT EXISTS `agent_operation_idempotency` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_type` varchar(32) NOT NULL COMMENT 'order or after_sale',
  `owner_id` varchar(64) NOT NULL COMMENT 'authenticated member id',
  `operation_key` varchar(128) NOT NULL COMMENT 'stable client/workflow operation id',
  `request_hash` char(64) NOT NULL COMMENT 'SHA-256 of canonical frozen request',
  `status` varchar(24) NOT NULL COMMENT 'PROCESSING or COMPLETED',
  `result_json` mediumtext DEFAULT NULL COMMENT 'exact committed business response',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_operation_owner_key` (`owner_type`, `owner_id`, `operation_key`),
  KEY `idx_agent_operation_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
