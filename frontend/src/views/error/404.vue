<template>
  <el-result icon="info" :title="t('error.notFound')">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">{{ t('layout.home') }}</el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
const { t } = useI18n();
</script>