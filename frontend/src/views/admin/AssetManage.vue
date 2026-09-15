<template>
  <div class="asset-manage">
    <!-- 统计卡（与仪表盘 KPI 卡同款：白底卡 + 彩色图标徽章 + 顶部渐变条） -->
    <div class="kpi-row" v-if="stats">
      <div class="kpi-card" style="--accent: 96 165 250">
        <div class="kpi-top">
          <div class="kpi-icon"><el-icon><Files /></el-icon></div>
          <div class="kpi-meta">
            <div class="kpi-label">资产总数</div>
            <div class="kpi-value">{{ stats.total }}<span class="unit">件</span></div>
          </div>
        </div>
        <div class="kpi-bottom"><span class="kpi-trend flat">台账累计</span></div>
      </div>
      <div class="kpi-card" style="--accent: 52 211 153">
        <div class="kpi-top">
          <div class="kpi-icon round"><el-icon><CircleCheck /></el-icon></div>
          <div class="kpi-meta">
            <div class="kpi-label">在用资产</div>
            <div class="kpi-value">{{ byStatus('IN_USE') }}<span class="unit">件</span></div>
          </div>
        </div>
        <div class="kpi-bottom kpi-splits">
          <span class="split ok">闲置 {{ byStatus('IDLE') }}</span>
          <span class="split warn">维修 {{ byStatus('REPAIR') }}</span>
          <span class="split danger">报废 {{ byStatus('SCRAPPED') }}</span>
        </div>
      </div>
      <div class="kpi-card" style="--accent: 251 146 60">
        <div class="kpi-top">
          <div class="kpi-icon"><el-icon><Clock /></el-icon></div>
          <div class="kpi-meta">
            <div class="kpi-label">借出中</div>
            <div class="kpi-value">{{ stats.borrowed }}<span class="unit">件</span></div>
          </div>
        </div>
        <div class="kpi-bottom"><span class="kpi-trend flat">未归还</span></div>
      </div>
      <div class="kpi-card" style="--accent: 167 139 250">
        <div class="kpi-top">
          <div class="kpi-icon"><el-icon><Coin /></el-icon></div>
          <div class="kpi-meta">
            <div class="kpi-label">资产总值</div>
            <div class="kpi-value">¥{{ formatMoney(stats.totalValue) }}</div>
          </div>
        </div>
        <div class="kpi-bottom"><span class="kpi-trend flat">购置金额合计</span></div>
      </div>
    </div>

    <!-- 工具条 -->
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="搜索编号/名称/品牌/型号/SN/责任人" clearable style="width:260px"
                @keyup.enter="onSearch" @clear="onSearch" />
      <el-tree-select v-model="filters.categoryId" :data="catTree" :render-after-expand="false"
                      check-strictly placeholder="全部分类" clearable style="width:180px" />
      <el-select v-model="filters.status" placeholder="状态" clearable style="width:120px">
        <el-option v-for="(m,k) in STATUS_META" :key="k" :label="m.label" :value="k" />
      </el-select>
      <el-select v-model="filters.borrowStatus" placeholder="借出状态" clearable style="width:130px">
        <el-option label="可借" value="AVAILABLE" />
        <el-option label="借出中" value="BORROWED" />
      </el-select>
      <el-input v-model="filters.ownerName" placeholder="责任人" clearable style="width:120px" @keyup.enter="onSearch" />
      <el-input v-model="filters.department" placeholder="部门" clearable style="width:120px" @keyup.enter="onSearch" />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <div class="spacer" />
      <el-button @click="onImport">批量导入</el-button>
      <el-button @click="onExport">导出 Excel</el-button>
      <el-button @click="onTemplate">下载模板</el-button>
      <el-button @click="openLabelDialog">批量打印标签</el-button>
      <el-button @click="openCategoryDialog">分类管理</el-button>
      <el-button type="primary" @click="openAdd">新增资产</el-button>
    </div>

    <!-- 批量操作条（勾选后出现，大厂后台表格通用交互） -->
    <div class="batch-bar" v-if="selectedRows.length">
      <span class="batch-count">已选 <b>{{ selectedRows.length }}</b> 项</span>
      <el-button link type="primary" @click="clearSelection">清空</el-button>
      <div class="spacer" />
      <el-button size="small" type="danger" plain :loading="batchDeleting" @click="batchDelete">批量删除</el-button>
      <el-button size="small" @click="batchExport">批量导出</el-button>
      <el-button size="small" @click="batchPrint">批量打印标签</el-button>
    </div>

    <!-- 台账表 -->
    <el-table ref="tableRef" :data="rows" v-loading="loading" stripe border style="width:100%"
              @selection-change="onSelectionChange">
      <el-table-column type="selection" width="46" />
      <el-table-column prop="code" label="编号" width="120" />
      <el-table-column label="名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="name-cell">
            <el-image v-if="row.imageUrl" :src="row.imageUrl" fit="cover" class="cell-thumb" />
            <span v-else class="cell-thumb placeholder">{{ (row.name || '?').slice(0, 1) }}</span>
            <span>{{ row.name }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="分类" width="110" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{row}">
          <el-tag :type="STATUS_META[row.status]?.type || 'info'" size="small">{{ STATUS_META[row.status]?.label || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ownerName" label="责任人" width="100" show-overflow-tooltip />
      <el-table-column prop="department" label="部门" width="100" show-overflow-tooltip />
      <el-table-column prop="location" label="位置" width="120" show-overflow-tooltip />
      <el-table-column label="价格" width="110" align="right">
        <template #default="{row}">{{ row.price != null ? '¥' + formatMoney(row.price) : '-' }}</template>
      </el-table-column>
      <el-table-column label="借出" width="90">
        <template #default="{row}">
          <el-tag :type="borrowTag(row).type" size="small">{{ borrowTag(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{row}">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="确认删除该资产？" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination :current-page="page" :page-size="size" :total="total" :page-sizes="[15,30,50,100]"
                     layout="total, sizes, prev, pager, next" @current-change="onPage" @size-change="onSize" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑资产' : '新增资产'" width="680px" @closed="resetForm">
      <el-form :model="form" label-width="92px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="资产编号">
              <el-input v-model="form.code" placeholder="留空自动生成" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分类">
              <el-tree-select v-model="form.categoryId" :data="catTree" :render-after-expand="false" check-strictly placeholder="请选择" clearable />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width:100%">
                <el-option v-for="(m,k) in STATUS_META" :key="k" :label="m.label" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="品牌"><el-input v-model="form.brand" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="型号"><el-input v-model="form.model" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="序列号"><el-input v-model="form.sn" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="责任人"><el-input v-model="form.ownerName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="部门"><el-input v-model="form.department" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="位置"><el-input v-model="form.location" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="购置日期"><el-date-picker v-model="form.purchaseDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="价格(元)"><el-input-number v-model="form.price" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="保修到期"><el-date-picker v-model="form.warrantyExpire" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="主图">
            <div class="upload-row">
              <el-input v-model="form.imageUrl" placeholder="图片 URL" style="flex:1" />
              <el-upload :show-file-list="false" :before-upload="onImageSelected" accept="image/*">
                <el-button>上传</el-button>
              </el-upload>
            </div>
            <img v-if="form.imageUrl" :src="form.imageUrl" class="preview" />
            <div class="field-tip">上传只是把图片放进服务器，还要点下方「保存 / 更新」才会写进这条资产（上传后不会自动保存）</div>
          </el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detail" :title="current?.name || '资产详情'" size="560px">
      <template v-if="current">
        <!-- 资产主图：此前详情抽屉没有渲染 imageUrl，导致上传成功也「看不到」 -->
        <div class="asset-photo">
          <el-image v-if="current.imageUrl" :src="current.imageUrl" fit="contain"
                    :preview-src-list="[current.imageUrl]" :initial-index="0" preview-teleported
                    class="photo-img" />
          <div v-else class="photo-empty">未上传主图</div>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编号">{{ current.code }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="STATUS_META[current.status]?.type || 'info'" size="small">{{ STATUS_META[current.status]?.label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ current.categoryName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="品牌/型号">{{ current.brand || '-' }} / {{ current.model || '-' }}</el-descriptions-item>
          <el-descriptions-item label="序列号">{{ current.sn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="责任人">{{ current.ownerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ current.department || '-' }}</el-descriptions-item>
          <el-descriptions-item label="位置">{{ current.location || '-' }}</el-descriptions-item>
          <el-descriptions-item label="购置日期">{{ current.purchaseDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="价格">{{ current.price != null ? '¥' + formatMoney(current.price) : '-' }}</el-descriptions-item>
          <el-descriptions-item label="保修到期">{{ current.warrantyExpire || '-' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ current.supplier || '-' }}</el-descriptions-item>
          <el-descriptions-item label="借出状态">
            <el-tag :type="current.borrowStatus === 'BORROWED' ? 'warning' : 'success'" size="small">
              {{ current.borrowStatus === 'BORROWED' ? '借出中' : '可借' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ current.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="qr-block" v-if="qrDataUrl">
          <div class="qr-title">资产二维码（扫码读取编号）</div>
          <img :src="qrDataUrl" class="qr-img" />
          <div class="qr-code-text">{{ current.code }}</div>
        </div>

        <el-divider />
        <div class="section-head">
          <span>借还记录</span>
          <el-button v-if="current.borrowStatus !== 'BORROWED'" type="primary" size="small"
                     :disabled="!isStatusBorrowable(current)"
                     :title="!isStatusBorrowable(current) ? '该资产状态不可借出' : ''"
                     @click="openBorrow">借出</el-button>
        </div>
        <el-table :data="borrows" size="small" border>
          <el-table-column prop="borrower" label="借用人" width="100" />
          <el-table-column prop="borrowReason" label="用途" show-overflow-tooltip />
          <el-table-column prop="borrowAt" label="借出时间" width="150" />
          <el-table-column label="状态" width="90">
            <template #default="{row}">
              <el-tag :type="row.status === 'BORROWED' ? 'warning' : 'success'" size="small">
                {{ row.status === 'BORROWED' ? '借出中' : '已归还' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{row}">
              <el-button v-if="row.status === 'BORROWED'" link type="primary" size="small" @click="openReturn(row)">归还</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider />
        <div class="section-head"><span>变更日志</span></div>
        <el-timeline v-if="logs.length">
          <el-timeline-item v-for="l in logs" :key="l.id" :timestamp="l.createdAt" placement="top">
            <div><b>{{ actionLabel(l.action) }}</b> · {{ l.operatorName || '系统' }}</div>
            <div class="log-detail">{{ l.detail }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无日志" />
      </template>
    </el-drawer>

    <!-- 借出弹窗 -->
    <el-dialog v-model="borrowDialog" title="借出资产" width="440px">
      <el-form :model="borrowForm" label-width="80px">
        <el-form-item label="借用人" required><el-input v-model="borrowForm.borrower" /></el-form-item>
        <el-form-item label="用途"><el-input v-model="borrowForm.borrowReason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="预计归还"><el-date-picker v-model="borrowForm.expectedReturnAt" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="borrowDialog=false">取消</el-button>
        <el-button type="primary" :loading="borrowSaving" @click="onBorrow">确认借出</el-button>
      </template>
    </el-dialog>

    <!-- 分类管理（大厂后台通用：树 + 行内操作） -->
    <el-dialog v-model="catDialog" title="资产分类管理" width="520px">
      <div class="cat-toolbar">
        <el-button size="small" type="primary" @click="startAddRoot">新增根分类</el-button>
        <span class="cat-hint">悬停分类可「加子项 / 编辑 / 删除」</span>
      </div>
      <el-tree ref="catTreeRef" :data="catTree" node-key="value"
               :props="{ label: 'label', children: 'children' }" default-expand-all
               :default-expanded-keys="expandedKeys" :expand-on-click-node="false"
               class="cat-tree" empty-text="暂无分类，点击「新增根分类」创建">
        <template #default="{ data }">
          <span class="cat-node">
            <span class="cat-node-name">{{ data.label }}</span>
            <span class="cat-node-actions">
              <el-button link type="primary" size="small" @click.stop="startAddChild(data)">加子项</el-button>
              <el-button link type="primary" size="small" @click.stop="startEdit(data)">编辑</el-button>
              <el-popconfirm title="删除该分类及其子分类？" @confirm="removeCategory(data)">
                <template #reference><el-button link type="danger" size="small" @click.stop>删除</el-button></template>
              </el-popconfirm>
            </span>
          </span>
        </template>
      </el-tree>
    </el-dialog>

    <!-- 分类新增/编辑 -->
    <el-dialog v-model="catEditDialog" :title="catEdit.id ? '编辑分类' : '新增分类'" width="420px" @closed="resetCatEdit">
      <el-form :model="catEdit" label-width="72px">
        <el-form-item label="上级分类">
          <el-input :model-value="catEdit.parentName" readonly placeholder="一级分类（无上级）" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="catEdit.name" maxlength="60" placeholder="如：笔记本电脑" @keyup.enter="saveCatEdit" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="catEdit.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="catEditDialog=false">取消</el-button>
        <el-button type="primary" :loading="catSaving" @click="saveCatEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入：上传 Excel → 返回成功/失败明细 -->
    <el-dialog v-model="importDialog" title="批量导入资产" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                title="请先下载模板按格式填写：资产名称必填，编号留空自动生成，状态填 在用/闲置/维修/报废。" />
      <el-upload drag :auto-upload="false" :limit="1" accept=".xlsx,.xls"
                 :on-change="onPickFile" :on-exceed="() => ElMessage.warning('只能选择一个文件')"
                 :file-list="importFileList">
        <div style="padding:12px 0">
          <div style="font-size:13px;color:#606266">将 Excel 拖到此处，或点击选择文件</div>
          <div style="font-size:12px;color:#909399;margin-top:4px">支持 .xlsx / .xls</div>
        </div>
      </el-upload>

      <div v-if="importResult" style="margin-top:14px">
        <el-alert :type="importResult.failedCount ? 'warning' : 'success'" :closable="false" show-icon
                  :title="`导入完成：成功 ${importResult.success} 条，失败 ${importResult.failedCount} 条`" />
        <el-table v-if="importResult.failed?.length" :data="importResult.failed" size="small"
                  max-height="220" border style="width:100%;margin-top:10px">
          <el-table-column prop="row" label="行号" width="70" />
          <el-table-column prop="name" label="资产名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="reason" label="失败原因" min-width="180" show-overflow-tooltip />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="importDialog=false">关闭</el-button>
        <el-button type="primary" :loading="importing" :disabled="!importFile" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 批量打印标签：A4 一页多码，弹出独立打印窗口 -->
    <el-dialog v-model="labelDialog" title="批量打印资产标签" width="480px">
      <el-form label-width="84px">
        <el-form-item label="打印范围">
          <el-radio-group v-model="labelRange">
            <el-radio value="page">当前页（{{ rows.length }} 条）</el-radio>
            <el-radio value="selected">选中项（{{ selectedRows.length }} 条）</el-radio>
            <el-radio value="filter">当前筛选全部</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="每页列数">
          <el-select v-model="labelCols" style="width:140px">
            <el-option label="1 列（大标签）" :value="1" />
            <el-option label="2 列" :value="2" />
            <el-option label="3 列" :value="3" />
            <el-option label="4 列" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="每页标签数">
          <el-select v-model="labelPerPage" style="width:140px">
            <el-option label="8（1×8）" :value="8" />
            <el-option label="18（2×9）" :value="18" />
            <el-option label="24（3×8）" :value="24" />
            <el-option label="32（4×8）" :value="32" />
          </el-select>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon
                  title="二维码内容为资产编号，配合扫码端点 /api/assets/{code} 使用；建议先用「下载模板 / 导入」建好数据，再批量打印。" />
      </el-form>
      <template #footer>
        <el-button @click="labelDialog=false">取消</el-button>
        <el-button type="primary" :loading="printing" @click="printLabels">生成并打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Files, CircleCheck, Clock, Coin } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { uploadFile } from '../../api/file'
import { escapeHtml } from '../../utils/text'
import { errMsg } from '../../utils/errMsg'
import {
  assetStats, nextAssetCode, listAssets, createAsset, updateAsset, deleteAsset,
  assetBorrows, borrowAsset, returnAsset, assetLogs, listAssetCategories,
  createAssetCategory, updateAssetCategory, deleteAssetCategory,
  exportAssets, downloadAssetTemplate, importAssets, saveBlob, batchDeleteAssets, getAsset
} from '../../api/asset'

const STATUS_META = {
  IN_USE: { label: '在用', type: 'success' },
  IDLE: { label: '闲置', type: 'info' },
  REPAIR: { label: '维修', type: 'warning' },
  SCRAPPED: { label: '报废', type: 'danger' },
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(15)
const stats = ref(null)
const categories = ref([])
const catTree = ref([])

const filters = reactive({ keyword: '', categoryId: null, status: '', borrowStatus: '', ownerName: '', department: '', location: '' })

const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = reactive(blankForm())
function blankForm() {
  return { id: null, code: '', name: '', categoryId: null, status: 'IN_USE', brand: '', model: '', sn: '',
    ownerName: '', department: '', location: '', purchaseDate: '', price: null, warrantyExpire: '', supplier: '', remark: '', imageUrl: '' }
}
function resetForm() { Object.assign(form, blankForm()) }

const detail = ref(false)
const current = ref(null)
const borrows = ref([])
const logs = ref([])
const qrDataUrl = ref('')

const borrowDialog = ref(false)
const borrowSaving = ref(false)
const borrowForm = reactive({ borrower: '', borrowReason: '', expectedReturnAt: '' })

const catDialog = ref(false)
const catTreeRef = ref(null)
const catEditDialog = ref(false)
const catSaving = ref(false)
const catEdit = reactive({ id: null, name: '', parentId: null, parentName: '', sort: 0 })

// 批量导入相关
const importDialog = ref(false)
const importFileList = ref([])
const importFile = ref(null)
const importResult = ref(null)
const importing = ref(false)

// 批量打印标签相关
const labelDialog = ref(false)
const labelRange = ref('page')
const labelCols = ref(3)
const labelPerPage = ref(24)
const printing = ref(false)

// 行勾选 + 表头全选（大厂后台表格通用交互）
const tableRef = ref(null)
const selectedRows = ref([])
const batchDeleting = ref(false)
function onSelectionChange(rows) { selectedRows.value = rows }
function clearSelection() { tableRef.value?.clearSelection() }

function byStatus(s) { return (stats.value && stats.value.byStatus && stats.value.byStatus[s]) || 0 }
function formatMoney(v) {
  if (v == null) return '0'
  const n = typeof v === 'string' ? Number(v) : v
  return (isNaN(n) ? 0 : n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function actionLabel(a) {
  return { CREATE: '建档', UPDATE: '更新', BORROW: '借出', RETURN: '归还', SCRAP: '报废', TRANSFER: '转移', DELETE: '删除' }[a] || a
}

// 借出字段与资产状态联动：报废 / 维修中的资产不可借出
function borrowTag(row) {
  if (row.borrowStatus === 'BORROWED') return { text: '借出中', type: 'warning' }
  if (row.status === 'SCRAPPED' || row.status === 'REPAIR') return { text: '不可借', type: 'info' }
  return { text: '可借', type: 'success' }
}
function isStatusBorrowable(row) {
  return row.status !== 'SCRAPPED' && row.status !== 'REPAIR'
}

const expandedKeys = computed(() => (categories.value || []).map(c => c.id))

function buildCatTree(list) {
  const map = {}
  list.forEach(c => { map[c.id] = { value: c.id, label: c.name, sort: c.sort || 0, children: [] } })
  const roots = []
  list.forEach(c => {
    if (c.parentId && map[c.parentId]) map[c.parentId].children.push(map[c.id])
    else roots.push(map[c.id])
  })
  const sortRec = (nodes) => { nodes.sort((a, b) => (a.sort || 0) - (b.sort || 0)); nodes.forEach(n => sortRec(n.children)) }
  sortRec(roots)
  return roots
}

async function loadCategories() {
  try {
    const { data } = await listAssetCategories()
    categories.value = data || []
    catTree.value = buildCatTree(categories.value)
  } catch (e) { /* ignore */ }
}

async function loadStats() {
  try { const { data } = await assetStats(); stats.value = data } catch (e) { /* ignore */ }
}

async function load() {
  loading.value = true
  try {
    const params = {
      keyword: filters.keyword, status: filters.status || null, borrowStatus: filters.borrowStatus || null,
      ownerName: filters.ownerName || null, department: filters.department || null, location: filters.location || null,
      categoryId: filters.categoryId || null, page: page.value - 1, size: size.value
    }
    const { data } = await listAssets(params)
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

const onSearch = () => { page.value = 1; load() }
const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }

function openAdd() { editing.value = null; resetForm(); dialog.value = true }
async function openEdit(row) {
  editing.value = row.id
  Object.assign(form, blankForm())
  Object.assign(form, { ...row })
  if (row.price != null) form.price = Number(row.price)
  dialog.value = true
}
async function onSave() {
  if (!form.name) { ElMessage.warning('请填写资产名称'); return }
  saving.value = true
  try {
    const payload = { ...form }
    if (editing.value) {
      await updateAsset(editing.value, payload)
      ElMessage.success('已更新')
    } else {
      await createAsset(payload)
      ElMessage.success('已建档')
    }
    dialog.value = false
    load(); loadStats()
  } catch (e) { ElMessage.error(errMsg(e, '保存失败')) }
  finally { saving.value = false }
}
async function onDelete(row) {
  try { await deleteAsset(row.id); ElMessage.success('已删除'); load(); loadStats() }
  catch (e) { ElMessage.error(errMsg(e, '删除失败')) }
}

async function openDetail(row) {
  current.value = row          // 先用列表数据即时渲染，避免抽屉空白
  detail.value = true
  qrDataUrl.value = ''
  // 再拉一次单条详情覆盖：列表行可能是本次保存前的旧对象，
  // 否则「刚上传的主图/刚改的字段」在详情里看不到
  try {
    const { data } = await getAsset(row.id)
    if (data && data.id) current.value = data
  } catch (e) { /* 拉取失败就沿用列表行 */ }
  try { qrDataUrl.value = await QRCode.toDataURL(row.code, { width: 220, margin: 1 }) } catch (e) {}
  try { const { data } = await assetBorrows(row.id); borrows.value = data || [] } catch (e) { borrows.value = [] }
  try { const { data } = await assetLogs(row.id); logs.value = data || [] } catch (e) { logs.value = [] }
}

function openBorrow() { borrowForm.borrower = ''; borrowForm.borrowReason = ''; borrowForm.expectedReturnAt = ''; borrowDialog.value = true }
async function onBorrow() {
  if (!borrowForm.borrower) { ElMessage.warning('请填写借用人'); return }
  borrowSaving.value = true
  try {
    await borrowAsset(current.value.id, { ...borrowForm })
    ElMessage.success('已借出')
    borrowDialog.value = false
    openDetail(current.value)
    load(); loadStats()
  } catch (e) { ElMessage.error(errMsg(e, '借出失败')) }
  finally { borrowSaving.value = false }
}
async function openReturn(b) {
  try {
    const { value } = await ElMessageBox.prompt('归还备注（可选）', '归还资产', { inputType: 'textarea', inputPlaceholder: '可填写归还情况' })
    await returnAsset(current.value.id, b.id, { returnRemark: value || '' })
    ElMessage.success('已归还')
    openDetail(current.value)
    load(); loadStats()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e, '归还失败')) }
}

function onImageSelected(file) {
  // 注意：axios 拦截器返回的是完整响应（res => res），上传接口返回 { errno, data: { url } }，
  // 因此路径必须是 r.data.data.url；写成 r.data.url 会取到 undefined，
  // 表现为「提示已上传、但输入框仍为空、保存后详情没有图」。
  uploadFile(file)
    .then((r) => {
      const url = r?.data?.data?.url
      if (!url) { ElMessage.error('上传失败：未返回图片地址'); return }
      form.imageUrl = url
      ElMessage.success('已上传，记得点「保存 / 更新」才会写进这条资产')
    })
    .catch(() => ElMessage.error('上传失败'))
  return false
}

// 分类管理（树 + 行内操作，避免左右两栏共享节点对象导致的状态污染）
function openCategoryDialog() { catDialog.value = true }
function resetCatEdit() { catEdit.id = null; catEdit.name = ''; catEdit.parentId = null; catEdit.parentName = ''; catEdit.sort = 0 }
function startAddRoot() { resetCatEdit(); catEditDialog.value = true }
function startAddChild(data) { resetCatEdit(); catEdit.parentId = data.value; catEdit.parentName = data.label; catEditDialog.value = true }
function startEdit(data) {
  resetCatEdit()
  catEdit.id = data.value
  catEdit.name = data.label
  const c = categories.value.find(x => x.id === data.value)
  catEdit.sort = (c && c.sort) || 0
  if (c && c.parentId != null) {
    catEdit.parentId = c.parentId
    const p = categories.value.find(x => x.id === c.parentId)
    catEdit.parentName = p ? p.name : ''
  }
  catEditDialog.value = true
}
async function saveCatEdit() {
  if (!catEdit.name || !catEdit.name.trim()) { ElMessage.warning('请填写分类名称'); return }
  catSaving.value = true
  try {
    const payload = { name: catEdit.name.trim(), parentId: catEdit.parentId, sort: catEdit.sort }
    if (catEdit.id) await updateAssetCategory(catEdit.id, payload)
    else await createAssetCategory(payload)
    ElMessage.success('已保存')
    catEditDialog.value = false
    await loadCategories()
  } catch (e) { ElMessage.error(errMsg(e, '保存失败')) }
  finally { catSaving.value = false }
}
async function removeCategory(data) {
  try { await deleteAssetCategory(data.value); ElMessage.success('已删除'); await loadCategories() }
  catch (e) { ElMessage.error(errMsg(e, '删除失败')) }
}

// 批量导入 / 导出 / 模板
function onImport() { importResult.value = null; importFile.value = null; importFileList.value = []; importDialog.value = true }
function onPickFile(file) { importFile.value = file.raw; importFileList.value = [file] }
async function doImport() {
  if (!importFile.value) { ElMessage.warning('请先选择 Excel 文件'); return }
  importing.value = true
  try {
    const { data } = await importAssets(importFile.value)
    importResult.value = data
    const ok = data.success || 0
    const fail = data.failedCount || 0
    if (ok) { ElMessage.success(`成功导入 ${ok} 条`); load(); loadStats() }
    if (fail) ElMessage.warning(`${fail} 条导入失败，见下方明细`)
    if (!ok && !fail) ElMessage.error('未读取到任何有效数据')
  } catch (e) {
    ElMessage.error(errMsg(e, '导入失败'))
  } finally { importing.value = false }
}
async function onTemplate() {
  try {
    const { data } = await downloadAssetTemplate()
    saveBlob(data, '资产导入模板.xlsx')
  } catch (e) { ElMessage.error('下载模板失败') }
}
async function onExport() {
  try {
    const params = {
      keyword: filters.keyword || null, status: filters.status || null, borrowStatus: filters.borrowStatus || null,
      ownerName: filters.ownerName || null, department: filters.department || null, location: filters.location || null,
      categoryId: filters.categoryId || null
    }
    const { data } = await exportAssets(params)
    const stamp = new Date().toISOString().slice(0, 10)
    saveBlob(data, `资产台账_${stamp}.xlsx`)
    ElMessage.success('已开始下载')
  } catch (e) { ElMessage.error(errMsg(e, '导出失败')) }
}

// 批量导出选中项（ids 以逗号串传给后端，Spring 自动按 List<Long> 解析）
async function batchExport() {
  const ids = selectedRows.value.map(r => r.id)
  if (!ids.length) return
  try {
    const { data } = await exportAssets({ ids: ids.join(',') })
    const stamp = new Date().toISOString().slice(0, 10)
    saveBlob(data, `资产台账_选中${ids.length}项_${stamp}.xlsx`)
    ElMessage.success('已开始下载')
  } catch (e) { ElMessage.error(errMsg(e, '批量导出失败')) }
}

// 批量打印选中项标签
function batchPrint() {
  if (!selectedRows.value.length) { ElMessage.warning('请先勾选要打印的资产'); return }
  labelRange.value = 'selected'
  labelDialog.value = true
}

// 批量删除：尽力删除，有未归还借用或已不存在的跳过并提示
async function batchDelete() {
  const ids = selectedRows.value.map(r => r.id)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 项资产？存在未归还借用记录或已不存在的将被跳过。`, '批量删除', { type: 'warning' })
  } catch (e) { return }
  batchDeleting.value = true
  try {
    const { data } = await batchDeleteAssets(ids)
    let msg = `已删除 ${data.deleted} 项`
    if (data.skipped) msg += `，跳过 ${data.skipped} 项（存在未归还借用或已不存在）`
    ElMessage.success(msg)
    clearSelection(); load(); loadStats()
  } catch (e) {
    ElMessage.error(errMsg(e, '批量删除失败'))
  } finally { batchDeleting.value = false }
}

// 批量打印标签：A4 一页多码，弹出独立打印窗口（二维码内容为资产编号）
function openLabelDialog() { labelDialog.value = true }
async function printLabels() {
  printing.value = true
  try {
    let assets = []
    if (labelRange.value === 'page') {
      assets = rows.value
    } else if (labelRange.value === 'selected') {
      if (!selectedRows.value.length) { ElMessage.warning('请先勾选要打印的资产'); return }
      assets = selectedRows.value
    } else {
      const params = {
        keyword: filters.keyword || null, status: filters.status || null, borrowStatus: filters.borrowStatus || null,
        ownerName: filters.ownerName || null, department: filters.department || null, location: filters.location || null,
        categoryId: filters.categoryId || null, page: 0, size: 2000
      }
      const { data } = await listAssets(params)
      assets = data.content || []
    }
    if (!assets.length) { ElMessage.warning('没有可打印的资产'); return }
    const items = await Promise.all(assets.map(async a => ({
      code: a.code,
      name: a.name,
      qr: await QRCode.toDataURL(a.code, { width: 200, margin: 1 })
    })))
    const w = window.open('', '_blank')
    if (!w) { ElMessage.error('打印窗口被拦截，请允许浏览器弹出窗口'); return }
    w.document.write(buildLabelHtml(items, labelCols.value, labelPerPage.value))
    w.document.close()
    w.focus()
    setTimeout(() => { try { w.print() } catch (e) { /* ignore */ } }, 350)
  } catch (e) {
    ElMessage.error('生成标签失败')
  } finally { printing.value = false }
}
function buildLabelHtml(items, cols, perPage) {
  const pages = []
  for (let i = 0; i < items.length; i += perPage) pages.push(items.slice(i, i + perPage))
  const labels = (arr) => arr.map(it => (
    '<div class="label">' +
      '<img src="' + it.qr + '" />' +
      '<div class="info"><div class="name">' + escapeHtml(it.name || '') + '</div>' +
      '<div class="code">' + escapeHtml(it.code || '') + '</div></div>' +
    '</div>'
  )).join('')
  const pageCls = cols === 1 ? 'page c1' : 'page'
  const body = pages.map(p => '<div class="' + pageCls + '">' + labels(p) + '</div>').join('')
  return '<!DOCTYPE html><html><head><meta charset="utf-8"><title>资产标签</title>' +
    '<style>@page { size: A4; margin: 6mm; } * { box-sizing: border-box; }' +
    'body { margin:0; font-family: "Microsoft YaHei", sans-serif; color:#000; }' +
    '.page { display:grid; grid-template-columns: repeat(' + cols + ', 1fr); gap: 4mm; page-break-after: always; }' +
    '.page:last-child { page-break-after: auto; }' +
    '.label { border:1px solid #333; border-radius:4px; padding:3mm; display:flex; gap:3mm; align-items:center; height:38mm; break-inside: avoid; overflow:hidden; }' +
    '.label img { width:30mm; height:30mm; flex:none; }' +
    '.label .info { flex:1; min-width:0; }' +
    '.label .name { font-size:11px; font-weight:700; line-height:1.25; max-height:28px; overflow:hidden; word-break:break-all; }' +
    '.label .code { font-size:10px; color:#444; margin-top:2px; font-family: ui-monospace, monospace; word-break:break-all; }' +
    /* 单列：标签变成横向长条，放大二维码与文字，避免大片留白 */
    '.page.c1 .label { height:26mm; padding:4mm; gap:5mm; }' +
    '.page.c1 .label img { width:24mm; height:24mm; }' +
    '.page.c1 .label .name { font-size:16px; max-height:24px; }' +
    '.page.c1 .label .code { font-size:13px; margin-top:4px; }' +
    '</style></head><body>' + body + '</body></html>'
}

onMounted(() => { load(); loadStats(); loadCategories() })
</script>

<style scoped>
.field-tip { flex-basis: 100%; color: var(--el-text-color-secondary, #94a3b8); font-size: 12px; line-height: 1.6; margin-top: 6px; }
/* 详情主图 */
.asset-photo {
  margin: 0 0 14px; min-height: 150px; border-radius: 12px;
  background: var(--el-fill-color-light, #f1f5f9);
  display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.photo-img { width: 100%; max-height: 320px; display: block; }
.photo-empty { color: var(--el-text-color-secondary, #94a3b8); font-size: 13px; padding: 56px 0; }
/* 列表名称单元格缩略图 */
.name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.cell-thumb { width: 26px; height: 26px; border-radius: 6px; flex: none; }
.cell-thumb.placeholder {
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--el-fill-color-light, #eef2f7); color: var(--el-text-color-secondary, #94a3b8);
  font-size: 12px; font-weight: 500;
}
/* ===== KPI 统计卡（同导入页风格，细节差异化） ===== */
.kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card {
  --accent: 96 165 250;
  position: relative;
  display: flex; flex-direction: column; justify-content: space-between;
  padding: 15px 16px 13px;
  background: #fff;
  border: 1px solid #edf1f6; border-radius: 12px;
  box-shadow: 0 1px 3px rgb(16 24 40 / .05);
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
/* 右上角淡色斜切角：代替卡片渐变底，更干净 */
.kpi-card::after {
  content: ''; position: absolute; right: -22px; top: -22px;
  width: 74px; height: 74px; border-radius: 24px;
  background: rgb(var(--accent) / .07);
  transform: rotate(24deg);
  pointer-events: none;
}
.kpi-card:hover { transform: translateY(-2px); border-color: rgb(var(--accent) / .45); box-shadow: 0 8px 18px -8px rgb(var(--accent) / .35); }
html.dark .kpi-card { background: #1e293b; border-color: rgba(255, 255, 255, .10); box-shadow: 0 2px 8px -4px rgba(0, 0, 0, .40); }
html.dark .kpi-card::after { background: rgb(var(--accent) / .12); }
html.dark .kpi-card:hover { border-color: rgb(var(--accent) / .45); }
.kpi-top { display: flex; align-items: center; gap: 12px; position: relative; z-index: 1; }
/* 渐变徽章：比导入页小一号，两段渐变 + 单层投影，观感更轻 */
.kpi-icon {
  position: relative; isolation: isolate; flex: none;
  width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center;
  font-size: 21px; color: #fff;
  background: linear-gradient(135deg,
              color-mix(in srgb, rgb(var(--accent)), #fff 22%),
              color-mix(in srgb, rgb(var(--accent)), #000 8%));
  box-shadow: 0 5px 12px -5px rgb(var(--accent) / .60);
}
/* 背后只留一枚浅色圆环，与导入页的双形状区分开 */
.kpi-icon::before {
  content: ''; position: absolute; z-index: -1; right: -6px; bottom: -7px;
  width: 16px; height: 16px; border-radius: 50%;
  border: 3px solid rgb(var(--accent) / .30);
  background: transparent;
}
.kpi-icon.round { border-radius: 50%; }
.kpi-icon.round::before { right: -4px; }
.kpi-meta { min-width: 0; }
.kpi-label { font-size: 12px; color: #94a3b8; white-space: nowrap; letter-spacing: .5px; }
html.dark .kpi-label { color: #9aa0ad; }
.kpi-value { font-size: 24px; font-weight: 700; line-height: 1.2; color: #0f172a; font-variant-numeric: tabular-nums; letter-spacing: -.3px; }
html.dark .kpi-value { color: #f1f5f9; }
.kpi-value .unit { font-size: 12px; font-weight: 500; margin-left: 3px; color: #94a3b8; }
.kpi-bottom { display: flex; align-items: center; gap: 10px; margin-top: 10px; height: 24px; position: relative; z-index: 1; }
.kpi-trend { font-size: 12px; font-weight: 400; color: #b6becd; }
html.dark .kpi-trend { color: #6b7280; }
.kpi-splits { justify-content: flex-end; }
.kpi-splits .split { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; }
.kpi-splits .split.ok { color: #4fbe87; }
.kpi-splits .split.warn { color: #d97706; }
.kpi-splits .split.danger { color: #ef6d59; }
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
.toolbar .spacer { flex: 1; }
.batch-bar { display: flex; align-items: center; gap: 10px; padding: 10px 14px; margin-bottom: 12px;
  background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 8px; color: #3730a3; }
.batch-bar .batch-count { font-size: 13px; }
.batch-bar .batch-count b { font-size: 15px; }
.batch-bar .spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.upload-row { display: flex; gap: 8px; align-items: center; width: 100%; }
.preview { width: 80px; height: 80px; object-fit: cover; border-radius: 8px; margin-top: 8px; border: 1px solid #e5e7eb; }
.qr-block { margin-top: 14px; text-align: center; }
.qr-title { font-size: 13px; color: #64748b; margin-bottom: 8px; }
.qr-img { width: 200px; height: 200px; border: 1px solid #e5e7eb; border-radius: 10px; padding: 8px; background: #fff; }
.qr-code-text { font-family: ui-monospace, monospace; font-weight: 700; margin-top: 6px; }
.section-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; margin-bottom: 8px; }
.log-detail { color: #64748b; font-size: 12px; margin-top: 2px; }
.cat-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.cat-hint { font-size: 12px; color: #909399; }
.cat-tree { border: 1px solid #e5e7eb; border-radius: 8px; padding: 6px; min-height: 240px; max-height: 52vh; overflow: auto; }
.cat-node { display: flex; align-items: center; justify-content: space-between; width: 100%; padding-right: 8px; }
.cat-node-name { font-size: 14px; }
.cat-node-actions { display: none; gap: 2px; }
.cat-node:hover .cat-node-actions { display: inline-flex; }
</style>
