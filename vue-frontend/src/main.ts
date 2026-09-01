import { createApp } from 'vue'
import VueFinderPlugin from 'vuefinder'
import zhCN from 'vuefinder/dist/locales/zhCN.js'
import App from './App.vue'
import 'vuefinder/dist/vuefinder.css'
import './styles.css'

const app = createApp(App)
app.use(VueFinderPlugin, {
  locale: 'zhCN',
  i18n: { zhCN },
})
app.mount('#app')
