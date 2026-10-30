package dev.codexpocket.app

object UpdateVersion {
    private val pattern=Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?")
    fun parts(value:String):List<java.math.BigInteger>{
        require(value.length<=64&&pattern.matches(value)){"更新版本号无效。"}
        return value.split('.').map{it.toBigInteger()}.let{it+List(4-it.size){java.math.BigInteger.ZERO}}
    }
    fun compare(a:String,b:String):Int{
        val left=parts(a);val right=parts(b)
        for(index in 0..3){val comparison=left[index].compareTo(right[index]);if(comparison!=0)return comparison}
        return 0
    }
}
