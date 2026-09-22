<template>
  <el-result icon="warning" :title="t('error.forbidden')">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">{{ t('layout.home') }}</el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
const { t } = useI18n();
</script>