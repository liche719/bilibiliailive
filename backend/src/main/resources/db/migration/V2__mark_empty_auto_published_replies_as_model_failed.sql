UPDATE reply_candidates
SET status = 'MODEL_FAILED',
    decision_reason = '模型未返回可展示的回复，未上屏'
WHERE status = 'AUTO_PUBLISHED'
  AND (candidate_text IS NULL OR BTRIM(candidate_text) = '');
