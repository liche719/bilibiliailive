ALTER TABLE reply_candidates
    ADD COLUMN platform VARCHAR(32),
    ADD COLUMN sender_id VARCHAR(128),
    ADD COLUMN message_id VARCHAR(128),
    ADD COLUMN occurred_at TIMESTAMP WITH TIME ZONE;

UPDATE reply_candidates
SET platform = 'MOCK',
    sender_id = 'legacy',
    message_id = id::text,
    occurred_at = created_at
WHERE platform IS NULL
   OR sender_id IS NULL
   OR message_id IS NULL
   OR occurred_at IS NULL;

UPDATE reply_candidates
SET status = 'AUTO_PUBLISHED'
WHERE status = 'APPROVED';

UPDATE reply_candidates
SET status = 'BLOCKED',
    decision_reason = COALESCE(decision_reason, '历史记录：未发布')
WHERE status = 'REJECTED';

ALTER TABLE reply_candidates
    ALTER COLUMN platform SET NOT NULL,
    ALTER COLUMN sender_id SET NOT NULL,
    ALTER COLUMN message_id SET NOT NULL,
    ALTER COLUMN occurred_at SET NOT NULL;

CREATE UNIQUE INDEX reply_candidates_platform_room_message_idx
    ON reply_candidates (platform, room_id, message_id);
