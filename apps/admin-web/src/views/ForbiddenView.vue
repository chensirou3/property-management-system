<template>
  <el-result icon="warning" title="无权访问" sub-title="当前角色没有该页面或项目的数据权限。">
    <template #extra><el-button type="primary" @click="$router.push('/dashboard')">返回工作台</el-button></template>
  </el-result>
</template>
