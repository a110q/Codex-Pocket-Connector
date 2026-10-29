package dev.codexpocket.app

import java.io.File

// Only fixed stage names, timings, status codes and exception class names are
// recorded. No pairing keys, authorization headers, prompts or chat data.
class ConnectionLog(private val file:File) {
    @Synchronized fun record(line:String){
        try{
            if(file.length()>65536){val previous=File(file.path+".previous");previous.delete();file.renameTo(previous)}
            file.appendText(line+"\n")
        }catch(_:Exception){}
    }
    @Synchronized fun recent():String=try{file.readLines().takeLast(50).joinToString("\n")}catch(_:Exception){"暂无连接诊断记录"}
}
