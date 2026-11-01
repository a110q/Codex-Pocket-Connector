package dev.codexpocket.app

internal data class PatchFile(val path:String,val added:Int,val removed:Int)

// Count only unified-diff hunks: headers, metadata and prose are not changed lines.
internal fun patchFiles(patch:String,fallbackPath:String?=null,fileChange:Boolean=false):List<PatchFile>{
    val files=linkedMapOf<String,Pair<Int,Int>>()
    var path=fallbackPath
    var hunk=false
    var before:Int?=null;var after:Int?=null
    val hunkHeader=Regex("^@@ -[0-9]+(?:,([0-9]+))? \\+[0-9]+(?:,([0-9]+))? @@.*")
    fun clean(value:String):String{
        val name=value.substringBefore('\t').trim().removeSurrounding("\"")
        return if(name.startsWith("a/")||name.startsWith("b/"))name.substring(2)else name
    }
    fun rememberPath(value:String){if(value.isNotBlank()&&value!="/dev/null"){path=value;files.putIfAbsent(value,0 to 0)}}
    val lines=patch.lineSequence().toList()
    for((index,line) in lines.withIndex()){
        when{
            line.startsWith("diff --git ")->{hunk=false;path=null}
            !hunk&&line.startsWith("--- ")->{rememberPath(clean(line.removePrefix("--- ")))}
            !hunk&&line.startsWith("+++ ")->{
                val newPath=clean(line.removePrefix("+++ "))
                if(newPath!="/dev/null"&&newPath!=path)path?.let{if(files[it]==(0 to 0))files.remove(it)}
                rememberPath(newPath)
            }
            line.startsWith("rename to ")->{path?.let{if(files[it]==(0 to 0))files.remove(it)};rememberPath(line.removePrefix("rename to "))}
            line.startsWith("rename from ")->{rememberPath(line.removePrefix("rename from "))}
            hunkHeader.matches(line)->{
                val match=hunkHeader.matchEntire(line)!!
                before=match.groups[1]?.value?.toIntOrNull()?:1;after=match.groups[2]?.value?.toIntOrNull()?:1
                hunk=true;path?.let{files.putIfAbsent(it,0 to 0)}
            }
            fileChange&&!hunk&&line.isNotBlank()&&!line.startsWith("diff ")&&
                (index==0||lines.getOrNull(index+1)?.let{it.startsWith("@@ ")||it.startsWith("diff --git ")||it.startsWith("--- ")}==true)->{
                rememberPath(line.trim())
            }
            hunk&&path!=null->{
                val key=path!!;val counts=files[key]?: (0 to 0)
                when{
                    line.startsWith("+")->{files[key]=(counts.first+1) to counts.second;after=(after?:0)-1}
                    line.startsWith("-")->{files[key]=counts.first to (counts.second+1);before=(before?:0)-1}
                    line.startsWith(" ")->{before=(before?:0)-1;after=(after?:0)-1}
                    line.startsWith("\\")->Unit
                    else->hunk=false
                }
                if(before==0&&after==0)hunk=false
            }
        }
    }
    return files.map{(name,counts)->PatchFile(name,counts.first,counts.second)}
}
