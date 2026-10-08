package com.dennis.aichat
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val role: String, val text: String)
data class ResearchFact(val title: String, val url: String, val snippet: String, val timestamp: Long)

class KnowledgeDb(context: Context): SQLiteOpenHelper(context, "dennis_chat.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_thread_idx ON messages(conversation,id)")
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, snippet TEXT NOT NULL, timestamp INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun addMessage(thread: Long, role: String, body: String) {
        writableDatabase.execSQL("INSERT INTO messages(conversation,role,body,timestamp) VALUES(?,?,?,?)", arrayOf<Any>(thread,role,body,System.currentTimeMillis()))
    }
    fun getMessages(thread: Long, limit: Int = 60): List<ChatLine> {
        val out=ArrayList<ChatLine>()
        readableDatabase.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages WHERE conversation=? ORDER BY id DESC LIMIT ?) ORDER BY id",arrayOf(thread.toString(),limit.toString())).use { c ->
            while(c.moveToNext()) out.add(ChatLine(c.getString(0),c.getString(1)))
        }
        return out
    }
    fun newConversation(): Long = System.currentTimeMillis()
    fun put(fact: ResearchFact) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO facts(title,url,snippet,timestamp) VALUES(?,?,?,?)", arrayOf<Any>(fact.title,fact.url,fact.snippet,fact.timestamp))
    }
    fun search(question: String, limit: Int = 4): List<ResearchFact> {
        val words=Regex("[a-zA-Z0-9]{4,}").findAll(question.lowercase()).map { it.value }.distinct().take(6).toList()
        if(words.isEmpty()) return emptyList()
        val clauses=words.joinToString(" OR ") { "(lower(title) LIKE ? OR lower(snippet) LIKE ?)" }
        val args=words.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
        val result=ArrayList<ResearchFact>()
        readableDatabase.rawQuery("SELECT title,url,snippet,timestamp FROM facts WHERE $clauses ORDER BY timestamp DESC LIMIT $limit",args).use { c ->
            while(c.moveToNext()) result.add(ResearchFact(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)))
        }
        return result
    }
    fun recent(limit: Int = 25): List<ResearchFact> {
        val result=ArrayList<ResearchFact>()
        readableDatabase.rawQuery("SELECT title,url,snippet,timestamp FROM facts ORDER BY timestamp DESC LIMIT ?",arrayOf(limit.toString())).use { c ->
            while(c.moveToNext()) result.add(ResearchFact(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)))
        }
        return result
    }
}
