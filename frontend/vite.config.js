import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// BUG FIX (user report: "offline pe app black screen deta hai, kyunki abhi
// bhi URL/request se chal raha hai"): pehle build multiple files banata tha
// (index.html + alag /assets/*.js + /assets/*.css) — Android shell in sabko
// ek virtual local URL (WebViewAssetLoader) ke through serve karta tha. Yeh
// theek se kaam karna chahiye tha, lekin real device par offline hote hi
// in chhote JS/CSS sub-requests mein se kuch WebView tak reliably nahi
// pahunch rahe the — HTML load ho jaata tha (isliye loading-spinner hatta
// tha) lekin JS/CSS kabhi nahi, isliye React kabhi mount hi nahi hota tha
// aur khaali/black WebView reh jaata tha.
//
// Fix: `vite-plugin-singlefile` poora built app (JS + CSS + chhote assets)
// EK hi index.html ke andar inline kar deta hai (<script>/<style> tags ke
// andar seedha, base64 jahan zaroori ho) — production build mein ab koi
// alag /assets/*.js ya /assets/*.css file banti hi nahi. Android side
// (MainActivity.kt) is ek index.html ko seedha ek string ke roop mein padhta
// hai aur WebView ko loadDataWithBaseURL() se de deta hai — matlab app-shell
// khulne ke liye ab kisi bhi URL/request/network call ki zaroorat hi nahi,
// chahe intercepted/local hi kyun na ho. Bilkul waisa hi jaise ek native app
// ka layout pehle se compiled/embedded hota hai.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    // singlefile plugin ko chahiye ki koi code-splitting na ho — sab kuch
    // ek hi bundle mein jaaye taaki poora inline ho sake.
    cssCodeSplit: false,
    assetsInlineLimit: 100000000,
    rollupOptions: {
      output: {
        inlineDynamicImports: true,
      },
    },
  },
  server: {
    port: 5173,
  },
})
