CREATE TABLE system_setup (
    singleton_id TINYINT PRIMARY KEY,
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    deployment_mode VARCHAR(30) NOT NULL DEFAULT 'SINGLE_PROJECT',
    enterprise_id VARCHAR(36),
    community_id VARCHAR(36),
    initialized_by VARCHAR(36),
    initialized_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_system_setup_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_system_setup_mode CHECK (deployment_mode = 'SINGLE_PROJECT'),
    CONSTRAINT fk_system_setup_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
    CONSTRAINT fk_system_setup_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_system_setup_user FOREIGN KEY (initialized_by) REFERENCES sys_user(id)
);

INSERT INTO system_setup
    (singleton_id, initialized, deployment_mode, enterprise_id, community_id, initialized_by,
     initialized_at, version, created_at, updated_at)
VALUES
    (1, FALSE, 'SINGLE_PROJECT', NULL, NULL, NULL, NULL, 0,
     CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

-- Existing installations already have an administrator. Mark them initialized so upgrading
-- can never reopen the public first-run endpoint. Fresh databases have no sys_user yet and
-- remain eligible for the one-time web setup.
UPDATE system_setup
   SET initialized = TRUE,
       enterprise_id = (SELECT id FROM enterprise ORDER BY created_at, id LIMIT 1),
       initialized_by = (SELECT id FROM sys_user ORDER BY created_at, id LIMIT 1),
       initialized_at = CURRENT_TIMESTAMP(3),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE singleton_id = 1
   AND EXISTS (SELECT 1 FROM sys_user);

UPDATE system_setup s
   SET community_id = (
         SELECT id FROM community c
          WHERE c.enterprise_id=s.enterprise_id AND c.status='ACTIVE'
          ORDER BY c.created_at, c.id LIMIT 1
       ),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE s.singleton_id = 1
   AND s.initialized = TRUE;

-- Upgraded installations are also normalized to one active project. The selected project is
-- the earliest active project recorded above; no business rows are deleted.
UPDATE community c
JOIN system_setup s ON s.singleton_id=1 AND s.initialized=TRUE
   SET c.status=CASE WHEN c.id=s.community_id THEN 'ACTIVE' ELSE 'INACTIVE' END,
       c.version=c.version+1,
       c.updated_at=CURRENT_TIMESTAMP(3)
 WHERE c.enterprise_id=s.enterprise_id;

UPDATE organization_unit o
JOIN system_setup s ON s.singleton_id=1 AND s.initialized=TRUE
   SET o.status=CASE WHEN o.community_id IS NULL OR o.community_id=s.community_id THEN 'ACTIVE' ELSE 'INACTIVE' END,
       o.version=o.version+1,
       o.updated_at=CURRENT_TIMESTAMP(3)
 WHERE o.enterprise_id=s.enterprise_id;
