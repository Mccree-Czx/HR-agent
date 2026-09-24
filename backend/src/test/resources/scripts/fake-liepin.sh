#!/bin/sh
# liepin-cli 桩脚本(测试用)
# - 首个参数 sleep-long:睡 60 秒(验证超时)
# - 其余情况行为由 FAKE_MODE 环境变量控制
if [ "$1" = "sleep-long" ]; then
  sleep 60
  exit 0
fi
case "$FAKE_MODE" in
  success-search)
    echo '[{"rank":1,"name":"张三","resume_id":"r1001","salary":"20-30K","city":"北京","experience":"5年"},{"rank":2,"name":"李四","resume_id":"r1002","salary":"15-25K","city":"上海","experience":"3年"}]'
    ;;
  echo-env)
    echo "dataDir=$LIEPIN_USER_DATA_DIR port=$LIEPIN_BROWSER_REMOTE_DEBUGGING_PORT"
    ;;
  sleep-long)
    sleep 60
    ;;
  risk)
    echo "302 to safe.liepin.com/captchaPage_PC 行为异常"
    ;;
  not-login)
    echo "请先登录后再操作"
    ;;
  fail)
    echo "some error occurred"
    exit 1
    ;;
  *)
    echo '[{"name":"默认候选人","resume_id":"r0"}]'
    ;;
esac
exit 0
