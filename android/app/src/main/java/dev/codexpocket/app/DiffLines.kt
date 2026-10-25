package dev.codexpocket.app

data class DiffLine(val text:String,val oldLine:Int?=null,val newLine:Int?=null,val kind:String="meta")
fun unifiedDiffLines(diff:String):List<DiffLine>{
    var old=0;var next=0;var hunk=false
    val header=Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    return diff.lines().map{line->
        val match=header.matchEntire(line)
        if(match!=null){old=match.groupValues[1].toInt();next=match.groupValues[2].toInt();hunk=true;DiffLine(line)}
        else if(!hunk||line.startsWith("\\"))DiffLine(line)
        else when(line.firstOrNull()){
            '+'->DiffLine(line,newLine=next++,kind="add")
            '-'->DiffLine(line,oldLine=old++,kind="remove")
            ' '->DiffLine(line,oldLine=old++,newLine=next++,kind="context")
            else->DiffLine(line)
        }
    }
}
