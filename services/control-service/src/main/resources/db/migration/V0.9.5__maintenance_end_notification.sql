-- 维护窗口"即将结束"通知的一次性守卫列（maintenance_ending webhook 派发防重）
-- 即将开始通知复用既有 notification_sent 列（V0.6.4，此前零业务读写）
ALTER TABLE maintenance_windows ADD COLUMN end_notification_sent BOOLEAN DEFAULT false;
