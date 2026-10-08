package com.dennis.aichat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val background=Color.rgb(10,15,25)
    private val panel=Color.rgb(22,30,45)
    private val accent=Color.rgb(73,147,237)
    private val muted=Color.rgb(157,170,189)
    private lateinit var db:KnowledgeDb
    private lateinit var keyVault:SecureKey
    private val model=LocalBrain()
    private val worker=Executors.newSingleThreadExecutor()
    private val research=BraveResearch()
    private lateinit var messages:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var composer:EditText
    private lateinit var send:Button
    private lateinit var status:TextView
    private lateinit var researchButton:Button
    private lateinit var preferences:android.content.SharedPreferences
    private var thread=0L
    private var researchEnabled=false
    private var busy=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=background
        window.navigationBarColor=background
        db=KnowledgeDb(this)
        keyVault=SecureKey(this)
        preferences=getSharedPreferences("options",MODE_PRIVATE)
        thread=preferences.getLong("thread",0L).takeIf { it>0L } ?: db.newConversation()
        researchEnabled=preferences.getBoolean("research",false)
        draw()
        val history=db.getMessages(thread)
        if(history.isEmpty()) welcome() else history.forEach { bubble(it.role,it.text) }
        val modelFile=File(filesDir,"brain.litertlm")
        status.text=if(modelFile.exists()) "Model imported · ready to initialise" else "No model yet · tap Import model"
    }
    private fun dp(value:Int)=(resources.displayMetrics.density*value+0.5f).toInt()
    private fun shape(color:Int,radius:Int=14):GradientDrawable=GradientDrawable().apply {
        setColor(color)
        cornerRadius=dp(radius).toFloat()
    }
    private fun label(message:String,size:Float=14f,color:Int=Color.WHITE,bold:Boolean=false):TextView=
        TextView(this).apply {
            text=message; textSize=size; setTextColor(color)
            if(bold) setTypeface(null,Typeface.BOLD)
        }
    private fun button(caption:String,fill:Int=panel):Button=Button(this).apply {
        text=caption; textSize=12f; isAllCaps=false
        setTextColor(Color.WHITE); background=shape(fill,12)
        setPadding(dp(12),dp(7),dp(12),dp(7))
        minHeight=dp(38); minimumHeight=dp(38)
    }
    private fun line():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
    }
    private fun margin(left:Int=0,top:Int=0,right:Int=0,bottom:Int=0):LinearLayout.LayoutParams=
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left),dp(top),dp(right),dp(bottom))
        }
    private fun draw() {
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(15),dp(14),dp(15),dp(10))
        }
        root.addView(label("DENNIS  /  AI CHAT",21f,Color.WHITE,true))
        root.addView(label("Private local intelligence · online research when you choose",11.5f,muted),margin(top=3,bottom=10))
        val first=line()
        val importButton=button("Import model",accent)
        importButton.setOnClickListener { importModel() }
        first.addView(importButton,margin(right=6))
        val settings=button("Search key")
        settings.setOnClickListener { showSettings() }
        first.addView(settings,margin(right=6))
        val newChat=button("New chat")
        newChat.setOnClickListener { newConversation() }
        first.addView(newChat)
        root.addView(first,margin(bottom=7))
        val second=line()
        researchButton=button(if(researchEnabled) "Research ON" else "Research OFF",if(researchEnabled) accent else panel)
        researchButton.setOnClickListener {
            researchEnabled=!researchEnabled
            preferences.edit().putBoolean("research",researchEnabled).apply()
            researchButton.text=if(researchEnabled) "Research ON" else "Research OFF"
            researchButton.background=shape(if(researchEnabled) accent else panel)
        }
        second.addView(researchButton,margin(right=6))
        val knowledge=button("Saved knowledge")
        knowledge.setOnClickListener { showKnowledge() }
        second.addView(knowledge)
        root.addView(second,margin(bottom=8))
        status=label("Starting…",12f,muted)
        root.addView(status,margin(bottom=10))
        scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
        messages=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM
            setPadding(dp(2),dp(8),dp(2),dp(12))
        }
        scroll.addView(messages)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val bottom=line().apply { setPadding(0,dp(9),0,0) }
        composer=EditText(this).apply {
            hint="Ask anything…"; setHintTextColor(muted); setTextColor(Color.WHITE)
            background=shape(panel,18); textSize=15f
            minLines=1; maxLines=4
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14),dp(12),dp(12),dp(12))
        }
        bottom.addView(composer,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        send=button("Send",accent)
        send.setOnClickListener { submit() }
        bottom.addView(send,margin(left=7))
        root.addView(bottom)
        setContentView(root)
    }
    private fun welcome() {
        bubble("assistant","Welcome. Import a compatible .litertlm model to enable genuine offline AI chat. Optional internet research needs a Brave Search API key. Research excerpts stay on your phone, but saved excerpts are not automatically verified facts.")
    }
    private fun bubble(role:String,body:String) {
        val isUser=role=="user"
        val textView=label(body,15f,Color.WHITE)
        textView.setPadding(dp(14),dp(11),dp(14),dp(11))
        textView.background=shape(if(isUser) Color.rgb(33,84,140) else panel,16)
        textView.autoLinkMask=Linkify.WEB_URLS
        textView.movementMethod=LinkMovementMethod.getInstance()
        val row=line().apply { gravity=if(isUser) Gravity.END else Gravity.START }
        val available=resources.displayMetrics.widthPixels-dp(75)
        row.addView(textView,LinearLayout.LayoutParams(available,LinearLayout.LayoutParams.WRAP_CONTENT))
        messages.addView(row,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(9)) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun setBusy(value:Boolean,explanation:String) {
        busy=value; send.isEnabled=!value; status.text=explanation
    }
    private fun submit() {
        if(busy) return
        val prompt=composer.text.toString().trim()
        if(prompt.isEmpty()) return
        val modelFile=File(filesDir,"brain.litertlm")
        if(!modelFile.exists()) { status.text="Import a compatible .litertlm model first"; return }
        val history=db.getMessages(thread,10)
        val currentThread=thread
        db.addMessage(currentThread,"user",prompt)
        bubble("user",prompt)
        composer.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(composer.windowToken,0)
        setBusy(true,"Preparing local inference…")
        val doResearch=researchEnabled
        worker.execute {
            var warning=""
            val live=if(doResearch) try {
                runOnUiThread { status.text="Searching the web…" }
                research.search(prompt,keyVault.load()).also { found -> found.forEach(db::put) }
            } catch(e:Exception) {
                warning="Research unavailable: ${e.message ?: "Connection error"}"
                emptyList()
            } else emptyList()
            try {
                runOnUiThread { status.text="Running local model on CPU…" }
                model.load(modelFile.absolutePath)
                val notes=(live+db.search(prompt)).distinctBy { it.url }.take(5)
                val answer=model.reply(prompt,history,notes)
                val refs=if(live.isNotEmpty()) "\n\nWeb results used as reference:\n"+
                    live.take(5).joinToString("\n") { "• ${it.title}: ${it.url}" } else ""
                val finalAnswer=answer+refs+if(warning.isNotBlank()) "\n\n$warning" else ""
                db.addMessage(currentThread,"assistant",finalAnswer)
                runOnUiThread {
                    if(currentThread==thread) {
                        bubble("assistant",finalAnswer)
                        setBusy(false,"Ready · on-device inference")
                    }
                }
            } catch(e:Exception) {
                val error="Model error: ${e.message ?: e.javaClass.simpleName}. Please check that the imported model is CPU-compatible with LiteRT-LM."
                runOnUiThread {
                    if(currentThread==thread) {
                        bubble("assistant",error)
                        setBusy(false,"Unable to generate")
                    }
                }
            }
        }
    }
    private fun newConversation() {
        if(busy) return
        thread=db.newConversation()
        preferences.edit().putLong("thread",thread).apply()
        messages.removeAllViews()
        welcome()
        status.text="New conversation · saved locally"
    }
    private fun showSettings() {
        val input=EditText(this).apply {
            hint="Paste Brave Search API key"
            setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(keyVault.load())
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        AlertDialog.Builder(this).setTitle("Internet research")
            .setMessage("Add your Brave Search API key. It is encrypted by Android Keystore, and sent only to Brave over HTTPS when you enable research.")
            .setView(input)
            .setPositiveButton("Save") { _,_->
                try { keyVault.save(input.text.toString().trim()); status.text="Search settings saved" }
                catch(e:Exception) { status.text="Secure key storage failed: ${e.message}" }
            }
            .setNegativeButton("Cancel",null).show()
    }
    private fun showKnowledge() {
        val saved=db.recent(20)
        val content=if(saved.isEmpty()) "No saved research yet. Enable Research and ask a question." else
            saved.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet.take(160)}" }
        AlertDialog.Builder(this).setTitle("Saved research (${saved.size} shown)")
            .setMessage(content).setPositiveButton("Close",null).show()
    }
    private fun importModel() {
        if(busy) return
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
        }
        startActivityForResult(intent,41)
    }
    @Deprecated("Use ActivityResult APIs when adding the androidx dependency")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=41 || resultCode!=RESULT_OK) return
        val uri:Uri=data?.data ?: return
        val field=android.provider.OpenableColumns.DISPLAY_NAME
        val display=contentResolver.query(uri,arrayOf(field),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else ""
        } ?: ""
        if(!display.endsWith(".litertlm",ignoreCase=true)) {
            status.text="Choose a LiteRT-LM .litertlm model (not a GGUF)"
            return
        }
        setBusy(true,"Importing model…")
        worker.execute {
            val target=File(filesDir,"brain.litertlm")
            val partial=File(filesDir,"brain.litertlm.tmp")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output,256*1024) }
                } ?: error("Could not open the selected file")
                check(partial.length()>10_000_000L) { "Model file is unexpectedly small" }
                model.close()
                if(target.exists() && !target.delete()) error("Unable to replace model")
                check(partial.renameTo(target)) { "Unable to activate model" }
                runOnUiThread { setBusy(false,"Model imported (${target.length()/1_048_576} MB) · ready") }
            } catch(e:Exception) {
                partial.delete()
                runOnUiThread { setBusy(false,"Model import failed: ${e.message}") }
            }
        }
    }
    override fun onDestroy() {
        worker.execute { model.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
