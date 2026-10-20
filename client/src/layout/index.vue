<template>
  <div class="app-wrapper">
    <!-- 若依经典深色侧边栏 -->
    <div class="sidebar-container" :class="{ 'is-collapse': isCollapse }">
      <div class="logo-wrap">
        <el-icon :size="24" color="#409eff"><Platform /></el-icon>
        <span v-if="!isCollapse" class="logo-title">若依 · 智能协同</span>
      </div>

      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
        :collapse="isCollapse"
        router
      >
        <el-menu-item index="/workbench">
          <el-icon><Odometer /></el-icon>
          <template #title>工作台看板</template>
        </el-menu-item>

        <el-menu-item index="/model-config">
          <el-icon><Cpu /></el-icon>
          <template #title>AI 模型热插拔中心</template>
        </el-menu-item>

        <el-menu-item index="/chat-agent">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>智能体对话工作台</template>
        </el-menu-item>
      </el-menu>
    </div>

    <!-- 主体区域 -->
    <div class="main-container" :class="{ 'is-collapse': isCollapse }">
      <!-- 顶部导航栏 (Navbar) -->
      <div class="navbar">
        <div class="navbar-left">
          <el-icon class="hamburger" @click="toggleSidebar">
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
          
          <el-breadcrumb separator="/" class="breadcrumb">
            <el-breadcrumb-item :to="{ path: '/workbench' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>AI 大模型治理</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentRouteTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="navbar-right">
          <el-dropdown trigger="click" @command="handleCommand">
            <div class="avatar-wrap">
              <el-avatar :size="32" class="user-avatar">
                {{ (userStore.displayName || 'U').slice(0, 1) }}
              </el-avatar>
              <span class="user-name">{{ userStore.displayName || '管理员' }}</span>
              <el-icon><CaretBottom /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="workbench">控制台首页</el-dropdown-item>
                <el-dropdown-item command="chat">智能体对话工作台</el-dropdown-item>
                <el-dropdown-item command="model">AI 模型配置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <!-- 内容渲染区 -->
      <div class="app-main">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Platform,
  Odometer,
  Cpu,
  ChatDotRound,
  Fold,
  Expand,
  CaretBottom
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapse = ref(false)
const toggleSidebar = () => {
  isCollapse.value = !isCollapse.value
}

const activeMenu = computed(() => route.path)
const currentRouteTitle = computed(() => route.meta.title || '多模型热插拔中心')

const handleCommand = (command) => {
  if (command === 'logout') {
    userStore.logout()
    ElMessage.info('已安全退出登录')
    router.push('/login')
  } else if (command === 'workbench') {
    router.push('/workbench')
  } else if (command === 'model') {
    router.push('/model-config')
  } else if (command === 'chat') {
    router.push('/chat-agent')
  }
}
</script>

<style scoped lang="scss">
.app-wrapper {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background-color: #f0f2f5;
}

.sidebar-container {
  width: 220px;
  height: 100%;
  background-color: #304156;
  transition: width 0.28s;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;

  &.is-collapse {
    width: 64px;
  }

  .logo-wrap {
    height: 50px;
    line-height: 50px;
    padding: 0 16px;
    background: #2b2f3a;
    display: flex;
    align-items: center;
    gap: 10px;
    overflow: hidden;

    .logo-title {
      color: #ffffff;
      font-weight: 600;
      font-size: 15px;
      white-space: nowrap;
    }
  }

  .sidebar-menu {
    border: none;
    flex: 1;
  }
}

.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.navbar {
  height: 50px;
  background: #ffffff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  z-index: 9;

  .navbar-left {
    display: flex;
    align-items: center;
    gap: 16px;

    .hamburger {
      font-size: 18px;
      cursor: pointer;
      color: #606266;

      &:hover {
        color: #409eff;
      }
    }

    .breadcrumb {
      font-size: 13px;
    }
  }

  .navbar-right {
    display: flex;
    align-items: center;
    gap: 16px;

    .status-tag {
      font-size: 11px;
    }

    .avatar-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;

      .user-avatar {
        background-color: #409eff;
        color: #fff;
        font-weight: 600;
      }

      .user-name {
        font-size: 13px;
        color: #606266;
      }
    }
  }
}

.app-main {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}
</style>
