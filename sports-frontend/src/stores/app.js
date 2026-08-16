import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/utils/request'

export const useAppStore = defineStore('app', () => {
  // State
  const currentMeet = ref(null)
  const meetList = ref([])
  const systemConfig = ref({
    siteName: '运动会智能编排系统',
    defaultLanes: 8,
    defaultHeats: 1,
    scoringRules: {
      first: 9,
      second: 7,
      third: 6,
      fourth: 5,
      fifth: 4,
      sixth: 3,
      seventh: 2,
      eighth: 1
    }
  })

  // Getters
  const meetName = computed(() => {
    return currentMeet.value?.name || '未选择运动会'
  })

  const meetId = computed(() => {
    return currentMeet.value?.id || null
  })

  const scoringMap = computed(() => {
    return systemConfig.value.scoringRules
  })

  // Actions
  async function fetchCurrentMeet() {
    try {
      const res = await request.get('/meets/current')
      currentMeet.value = res
      return res
    } catch (error) {
      console.error('获取当前运动会信息失败:', error)
      return null
    }
  }

  async function fetchMeetList() {
    try {
      const res = await request.get('/meets')
      meetList.value = Array.isArray(res) ? res : (res.records || res.list || [])
      return meetList.value
    } catch (error) {
      console.error('获取运动会列表失败:', error)
      return []
    }
  }

  async function setCurrentMeet(meet) {
    currentMeet.value = meet
  }

  async function fetchSystemConfig() {
    try {
      const res = await request.get('/system/config')
      if (res) {
        systemConfig.value = { ...systemConfig.value, ...res }
      }
      return systemConfig.value
    } catch (error) {
      console.error('获取系统配置失败:', error)
      return systemConfig.value
    }
  }

  return {
    currentMeet,
    meetList,
    systemConfig,
    meetName,
    meetId,
    scoringMap,
    fetchCurrentMeet,
    fetchMeetList,
    setCurrentMeet,
    fetchSystemConfig
  }
})
