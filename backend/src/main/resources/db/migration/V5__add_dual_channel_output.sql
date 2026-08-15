ALTER TABLE reply_candidates
    ADD COLUMN danmaku_text VARCHAR(160),
    ADD COLUMN danmaku_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN danmaku_platform_message_id VARCHAR(128),
    ADD COLUMN danmaku_decision_reason VARCHAR(256);

ALTER TABLE runtime_control_events
    ADD COLUMN output_mode VARCHAR(32) NOT NULL DEFAULT 'OVERLAY_ONLY';
