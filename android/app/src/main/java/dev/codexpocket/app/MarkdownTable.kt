package dev.codexpocket.app

import android.text.Spanned
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.Table
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Document

sealed interface MarkdownPart {
    data class Prose(val content:Spanned):MarkdownPart
    data class Grid(val table:Table):MarkdownPart
}
internal fun markdownParts(markwon:Markwon,text:String):List<MarkdownPart> = buildList {
    val document=markwon.parse(text)
    var prose=Document()
    fun flush(){if(prose.firstChild!=null){add(MarkdownPart.Prose(markwon.render(prose)));prose=Document()}}
    var node=document.firstChild
    while(node!=null){
        val next=node.next
        if(node is TableBlock){flush();Table.parse(markwon,node)?.let{add(MarkdownPart.Grid(it))}}
        else{node.unlink();prose.appendChild(node)}
        node=next
    }
    flush()
}
@Composable internal fun MarkdownSpanned(content:Spanned,color:Int,linkColor:Int,modifier:Modifier=Modifier,alignment:Table.Alignment?=null,strong:Boolean=false){
    AndroidView(modifier=modifier,factory={TextView(it).apply{
        textSize=15.5f;includeFontPadding=false;setFallbackLineSpacing(false);setTextIsSelectable(true)
        movementMethod=android.text.method.LinkMovementMethod.getInstance()
        setLineHeight(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,25f,resources.displayMetrics).toInt())
    }},update={
        it.setTextColor(color);it.setLinkTextColor(linkColor);it.text=content;it.setTypeface(null,if(strong)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        it.gravity=when(alignment){Table.Alignment.CENTER->android.view.Gravity.CENTER_HORIZONTAL;Table.Alignment.RIGHT->android.view.Gravity.RIGHT;else->android.view.Gravity.LEFT}
    })
}
@Composable internal fun MarkdownTable(table:Table,color:Int,linkColor:Int){
    val rows=table.rows()
    val count=rows.maxOfOrNull{it.columns().size}?:0
    if(count==0)return
    val widths=(0 until count).map{column->
        val length=rows.maxOf{it.columns().getOrNull(column)?.content()?.length?:0}
        (if(count<=2)maxOf(140,minOf(220,80+length*6))else maxOf(128,minOf(220,60+length*5))).dp
    }
    Column(Modifier.fillMaxWidth()){
        Surface(shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=MaterialTheme.colorScheme.surface){
            Column(Modifier.horizontalScroll(rememberScrollState()).semantics{contentDescription="表格，可横向滚动"}){
                rows.forEachIndexed{index,row->
                    if(index>0)HorizontalDivider()
                    Surface(color=if(row.header())MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface){
                        Row(Modifier.height(IntrinsicSize.Min)){
                            (0 until count).forEach{column->
                                if(column>0)VerticalDivider()
                                val cell=row.columns().getOrNull(column)
                                Box(Modifier.width(widths[column]).fillMaxHeight().padding(horizontal=12.dp,vertical=10.dp)){
                                    cell?.let{MarkdownSpanned(it.content(),color,linkColor,Modifier.fillMaxWidth(),it.alignment(),row.header())}
                                }
                            }
                        }
                    }
                }
            }
        }
        if(count>2)Text("左右滑动查看表格",Modifier.padding(top=4.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
