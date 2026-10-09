// للمشاريع التي تستخدم bundler (Vite/Webpack). مشروع HTML بسيط لا يحتاج هذا الملف.
import { registerPlugin } from '@capacitor/core';

export interface MyPluginPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}

const MyPlugin = registerPlugin<MyPluginPlugin>('MyPlugin');
export default MyPlugin;
