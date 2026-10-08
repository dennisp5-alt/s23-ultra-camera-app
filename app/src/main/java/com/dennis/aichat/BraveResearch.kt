package com.dennis.aichat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BraveResearch {
    fun search(query: String, apiKey: String): List<ResearchFact> {
        require(apiKey.isNotBlank()) { "Enter your Brave Search API key to use live research." }
        val encoded=URLEncoder.encode(query,"UTF-8")
        val connection=URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=5").openConnection() as HttpURLConnection
        connection.requestMethod="GET"
        connection.connectTimeout=12000
        connection.readTimeout=15000
        connection.setRequestProperty("Accept","application/json")
        connection.setRequestProperty("X-Subscription-Token",apiKey)
        try {
            if(connection.responseCode!=200) {
                val error=connection.errorStream?.bufferedReader()?.use { it.readText().take(200) } ?: ""
                throw IllegalStateException("Search HTTP ${connection.responseCode}: $error")
            }
            val json=connection.inputStream.bufferedReader().use { it.readText() }
            val array=JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            val result=ArrayList<ResearchFact>()
            val now=System.currentTimeMillis()
            for(i in 0 until array.length()) {
                val item=array.optJSONObject(i) ?: continue
                val link=item.optString("url")
                if(!link.startsWith("https://")) continue
                val title=item.optString("title").take(250)
                val description=item.optString("description").replace(Regex("<[^>]*>"),"").take(1800)
                if(description.isNotBlank()) result.add(ResearchFact(title,link,description,now))
            }
            return result
        } finally { connection.disconnect() }
    }
}
