<template>
  <section class="page usage-analysis-page">
    <header class="page-header usage-header">
      <div>
        <h1 class="page-title">用量分析</h1>
        <p class="page-desc">
          按租户、项目、应用、Virtual Key、模型和供应商查看真实调用统计与明细。
        </p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="activeLoading" @click="refreshActive">
          <ReloadOutlined />刷新
        </button>
        <button
          class="btn"
          :disabled="!detailRows.length"
          @click="exportCurrentPage"
        >
          <DownloadOutlined />导出当前页
        </button>
      </div>
    </header>

    <section class="card usage-filter-card">
      <div class="filter-grid">
        <label class="filter-field time-field"
          ><span>开始时间</span
          ><input
            v-model="filters.startAt"
            type="datetime-local"
            class="input usage-input"
        /></label>
        <label class="filter-field time-field"
          ><span>结束时间</span
          ><input
            v-model="filters.endAt"
            type="datetime-local"
            class="input usage-input"
            @input="endAtTracksNow = false"
        /></label>
        <label class="filter-field"
          ><span>租户</span
          ><select v-model="filters.tenantId" class="usage-select">
            <option value="">全部租户</option>
            <option
              v-for="item in options.tenants"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
        <label class="filter-field"
          ><span>项目</span
          ><select v-model="filters.projectId" class="usage-select">
            <option value="">全部项目</option>
            <option
              v-for="item in options.projects"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
        <label class="filter-field"
          ><span>应用</span
          ><select v-model="filters.appId" class="usage-select">
            <option value="">全部应用</option>
            <option
              v-for="item in options.apps"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
        <label class="filter-field"
          ><span>Virtual Key</span
          ><select v-model="filters.apiKeyId" class="usage-select">
            <option value="">全部 Virtual Key</option>
            <option
              v-for="item in options.apiKeys"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
        <label class="filter-field"
          ><span>供应商</span
          ><select v-model="filters.providerId" class="usage-select">
            <option value="">全部供应商</option>
            <option
              v-for="item in options.providers"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
        <label class="filter-field"
          ><span>模型</span
          ><select v-model="filters.modelAlias" class="usage-select">
            <option value="">全部模型</option>
            <option
              v-for="item in options.models"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select></label
        >
      </div>
      <div class="filter-actions">
        <button
          class="btn primary"
          :disabled="activeLoading"
          @click="applyFilters"
        >
          查询</button
        ><button class="btn" @click="resetFilters">重置</button>
      </div>
      <nav class="usage-tabs" aria-label="用量分析视图">
        <button
          :class="['usage-tab', { active: activeTab === 'dashboard' }]"
          @click="switchTab('dashboard')"
        >
          <BarChartOutlined />看板统计
        </button>
        <button
          :class="['usage-tab', { active: activeTab === 'details' }]"
          @click="switchTab('details')"
        >
          <UnorderedListOutlined />明细列表
        </button>
      </nav>
    </section>

    <section
      v-if="activeTab === 'dashboard'"
      class="usage-view usage-dashboard-view"
    >
      <div v-if="dashboardError" class="state-panel error-state">
        <strong>统计看板加载失败</strong>
        <p>{{ dashboardError }}</p>
        <button class="btn" @click="loadDashboard">重试</button>
      </div>
      <div v-else-if="dashboardLoading" class="state-panel dashboard-loading">
        <span class="loading-mark"></span><strong>正在汇总多维用量数据</strong>
      </div>
      <template v-else>
        <section class="usage-kpi-grid">
          <article v-for="item in kpis" :key="item.key" class="usage-kpi-card">
            <span :class="['kpi-icon', item.tone]"
              ><component :is="item.icon"
            /></span>
            <div>
              <span class="kpi-label">{{ item.label }}</span
              ><strong>{{ item.value }}</strong
              ><em>{{ item.note }}</em>
            </div>
          </article>
        </section>

        <section class="analytics-grid">
          <article class="analytics-card trend-card span-2">
            <div class="analytics-heading trend-heading">
              <div>
                <span class="analytics-kicker">30 DAY PULSE</span>
                <h2>请求量 / Token / 成本趋势</h2>
                <p>各指标按自身峰值归一化，突出变化节奏</p>
              </div>
              <div class="trend-summary">
                <span
                  ><i class="blue-dot"></i>请求峰值<strong>{{
                    number(maxValue(dashboard.trend, "requests"))
                  }}</strong></span
                ><span
                  ><i class="green-dot"></i>Token 峰值<strong>{{
                    compact(maxValue(dashboard.trend, "tokens"))
                  }}</strong></span
                ><span
                  ><i class="violet-dot"></i>成本峰值<strong>{{
                    money(maxValue(dashboard.trend, "cost"))
                  }}</strong></span
                >
              </div>
            </div>
            <div v-if="hasTrend" class="line-chart-shell">
              <svg
                class="line-chart"
                viewBox="0 0 720 250"
                preserveAspectRatio="none"
                role="img"
                aria-label="用量趋势图"
              >
                <defs>
                  <linearGradient id="requestArea" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stop-color="#2563eb" stop-opacity=".22" />
                    <stop offset="1" stop-color="#2563eb" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <g class="grid-lines">
                  <line
                    v-for="y in [35, 80, 125, 170, 215]"
                    :key="y"
                    x1="48"
                    :y1="y"
                    x2="704"
                    :y2="y"
                  />
                </g>
                <g class="axis-scale">
                  <text x="10" y="39">100%</text>
                  <text x="16" y="84">75%</text>
                  <text x="16" y="129">50%</text>
                  <text x="16" y="174">25%</text>
                  <text x="26" y="219">0</text>
                </g>
                <polygon
                  :points="areaPoints(dashboard.trend, 'requests')"
                  fill="url(#requestArea)"
                />
                <polyline
                  :points="linePoints(dashboard.trend, 'requests')"
                  class="series requests"
                />
                <polyline
                  :points="linePoints(dashboard.trend, 'tokens')"
                  class="series tokens"
                />
                <polyline
                  :points="linePoints(dashboard.trend, 'cost')"
                  class="series cost"
                />
                <g v-for="(label, index) in trendLabels" :key="label.date">
                  <text :x="label.x" y="242" class="axis-label">
                    {{ shortDate(label.date) }}
                  </text>
                </g>
              </svg>
            </div>
            <div v-else class="chart-empty">当前筛选条件没有趋势数据</div>
          </article>

          <article class="analytics-card provider-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">PROVIDER MIX</span>
                <h2>按供应商成本占比</h2>
                <p>渠道真实成本构成</p>
              </div>
              <span class="unit-note">{{ summary.currency || "USD" }}</span>
            </div>
            <div v-if="providerSegments.length" class="donut-layout">
              <div class="donut-shell">
                <div class="donut" :style="{ background: donutBackground }">
                  <div>
                    <span>总成本</span
                    ><strong>{{ money(summary.costAmount) }}</strong
                    ><em>{{ summary.currency || "USD" }}</em
                    ><small>{{ providerSegments.length }} 家供应商</small>
                  </div>
                </div>
              </div>
              <div class="donut-legend">
                <div
                  v-for="item in providerSegments"
                  :key="item.name"
                  class="provider-legend-row"
                >
                  <div class="provider-legend-main">
                    <i :style="{ background: item.color }"></i
                    ><span :title="item.name">{{ item.name }}</span
                    ><strong>{{ item.percent.toFixed(1) }}%</strong>
                  </div>
                  <div class="provider-legend-meta">
                    <em>{{ money(item.cost) }}</em
                    ><small
                      >{{ compact(item.tokens) }} Token ·
                      {{ number(item.requests) }} 请求</small
                    >
                  </div>
                  <div class="provider-progress">
                    <i
                      :style="{
                        background: item.color,
                        width: `${Math.max(item.percent, 2)}%`,
                      }"
                    ></i>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="chart-empty">暂无供应商成本数据</div>
          </article>

          <article class="analytics-card model-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">MODEL TOP 10</span>
                <h2>按模型使用量</h2>
                <p>按 Token 消耗降序</p>
              </div>
              <span class="unit-note">Token</span>
            </div>
            <div v-if="dashboard.modelUsage?.length" class="horizontal-bars">
              <div
                v-for="(item, index) in dashboard.modelUsage"
                :key="item.name"
                class="horizontal-row"
              >
                <span :class="['rank', { top: index < 3 }]">{{
                  String(index + 1).padStart(2, "0")
                }}</span>
                <div class="model-info">
                  <span class="bar-name" :title="item.name">{{
                    item.name
                  }}</span
                  ><small>{{ number(item.requests) }} 请求</small>
                </div>
                <div class="model-bar-block">
                  <div class="bar-track">
                    <i
                      :style="{
                        width: barWidth(
                          item.tokens,
                          dashboard.modelUsage,
                          'tokens',
                        ),
                      }"
                    ></i>
                  </div>
                  <small
                    >占 TOP10
                    {{
                      share(item.tokens, dashboard.modelUsage, "tokens")
                    }}</small
                  >
                </div>
                <strong>{{ compact(item.tokens) }}</strong>
              </div>
            </div>
            <div v-else class="chart-empty">暂无模型使用数据</div>
          </article>

          <article class="analytics-card tenant-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">TENANT RANKING</span>
                <h2>按租户成本排名</h2>
                <p>部门与租户成本归因</p>
              </div>
              <span class="unit-note">{{ summary.currency || "USD" }}</span>
            </div>
            <div v-if="dashboard.tenantCost?.length" class="tenant-ranking">
              <div
                v-for="(item, index) in dashboard.tenantCost"
                :key="item.name"
                class="tenant-row"
              >
                <span :class="['tenant-index', { top: index < 3 }]">{{
                  index + 1
                }}</span>
                <div class="tenant-name">
                  <strong :title="item.name">{{ item.name }}</strong
                  ><small
                    >{{ number(item.requests) }} 请求 ·
                    {{ compact(item.tokens) }} Token</small
                  >
                </div>
                <div class="tenant-meter">
                  <i
                    :style="{
                      width: barWidth(item.cost, dashboard.tenantCost, 'cost'),
                    }"
                  ></i>
                </div>
                <strong class="tenant-cost">{{ money(item.cost) }}</strong>
              </div>
            </div>
            <div v-else class="chart-empty">暂无租户成本数据</div>
          </article>

          <article class="analytics-card project-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">PROJECT DISTRIBUTION</span>
                <h2>按项目用量分布</h2>
                <p>请求量与 Token 双维对比</p>
              </div>
              <div class="chart-legend">
                <span class="blue">请求</span><span class="green">Token</span>
              </div>
            </div>
            <div v-if="dashboard.projectUsage?.length" class="project-bars">
              <div
                v-for="item in dashboard.projectUsage"
                :key="item.name"
                class="project-row"
              >
                <div class="project-name">
                  <span :title="item.name">{{ item.name }}</span
                  ><small
                    >{{ money(item.cost) }}
                    {{ summary.currency || "USD" }}</small
                  >
                </div>
                <div class="project-metrics">
                  <div class="project-metric">
                    <span>Token</span>
                    <div class="metric-track">
                      <i
                        class="token-fill"
                        :style="{
                          width: barWidth(
                            item.tokens,
                            dashboard.projectUsage,
                            'tokens',
                          ),
                        }"
                      ></i>
                    </div>
                    <strong>{{ compact(item.tokens) }}</strong>
                  </div>
                  <div class="project-metric">
                    <span>请求</span>
                    <div class="metric-track">
                      <i
                        class="request-fill"
                        :style="{
                          width: barWidth(
                            item.requests,
                            dashboard.projectUsage,
                            'requests',
                          ),
                        }"
                      ></i>
                    </div>
                    <strong>{{ number(item.requests) }}</strong>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="chart-empty">暂无项目归因数据</div>
          </article>

          <article class="analytics-card app-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">APPLICATION FLOW</span>
                <h2>按应用请求趋势</h2>
                <p>Top 3 应用调用走势</p>
              </div>
            </div>
            <div v-if="appSeries.length" class="app-chart-shell">
              <div class="app-legend">
                <span v-for="series in appSeries" :key="series.name"
                  ><i :style="{ background: series.color }"></i
                  ><b>{{ series.name }}</b
                  ><em>{{ number(series.total) }} 请求</em></span
                >
              </div>
              <svg
                class="app-chart"
                viewBox="0 0 540 190"
                preserveAspectRatio="none"
              >
                <defs>
                  <linearGradient id="appArea" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stop-color="#2563eb" stop-opacity=".15" />
                    <stop offset="1" stop-color="#2563eb" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <g class="grid-lines">
                  <line
                    v-for="y in [30, 70, 110, 150]"
                    :key="y"
                    x1="24"
                    :y1="y"
                    x2="526"
                    :y2="y"
                  />
                </g>
                <polygon
                  v-if="appSeries[0]"
                  :points="appSeries[0].area"
                  fill="url(#appArea)"
                />
                <template v-for="series in appSeries" :key="series.name">
                  <polyline
                    :points="series.points"
                    class="app-series"
                    :style="{ stroke: series.color }"
                  />
                  <circle
                    v-for="point in series.dots"
                    :key="`${series.name}-${point.x}`"
                    :cx="point.x"
                    :cy="point.y"
                    r="2.6"
                    class="app-dot"
                    :style="{ fill: series.color }"
                  />
                </template>
                <text
                  v-for="label in appTrendLabels"
                  :key="label.date"
                  :x="label.x"
                  y="184"
                  class="axis-label"
                >
                  {{ shortDate(label.date) }}
                </text>
              </svg>
            </div>
            <div v-else class="chart-empty">暂无应用趋势数据</div>
          </article>

          <article class="analytics-card key-card">
            <div class="analytics-heading">
              <div>
                <span class="analytics-kicker">KEY ATTRIBUTION</span>
                <h2>Virtual Key TOP 10</h2>
                <p>按 Token 消耗排序</p>
              </div>
              <button class="text-action" @click="switchTab('details')">
                查看全部明细 →
              </button>
            </div>
            <div v-if="dashboard.keyRanking?.length" class="ranking-table">
              <div class="ranking-head">
                <span>排名</span><span>Virtual Key</span><span>请求数</span
                ><span>Token</span><span>成本</span>
              </div>
              <div
                v-for="(item, index) in dashboard.keyRanking"
                :key="item.name"
                class="ranking-row"
              >
                <span :class="['ranking-index', { top: index < 3 }]">{{
                  index + 1
                }}</span
                ><strong>{{ item.name }}</strong
                ><span>{{ number(item.requests) }}</span
                ><span>{{ number(item.tokens) }}</span
                ><span>{{ money(item.cost) }}</span>
              </div>
            </div>
            <div v-else class="chart-empty">暂无 Virtual Key 用量数据</div>
          </article>
        </section>
      </template>
    </section>

    <section v-else class="usage-view usage-detail-view">
      <section class="card data-surface usage-detail-card">
        <div class="detail-list-toolbar">
          <div>
            <strong>明细列表</strong>
            <span>当前显示 {{ visibleDetailColumns.length }} / {{ detailColumns.length }} 个字段</span>
          </div>
          <details class="column-selector">
            <summary class="btn small">显示字段</summary>
            <div class="column-selector-panel">
              <div class="column-selector-head">
                <div>
                  <strong>选择显示字段</strong>
                  <span>至少保留一个字段</span>
                </div>
                <div>
                  <button class="text-action" type="button" @click="showAllDetailColumns">全部显示</button>
                  <button class="text-action" type="button" @click="resetDetailColumns">恢复默认</button>
                </div>
              </div>
              <div class="column-selector-grid">
                <label v-for="column in detailColumns" :key="column.key">
                  <input
                    type="checkbox"
                    :checked="selectedDetailColumnKeys.includes(column.key)"
                    :disabled="selectedDetailColumnKeys.length === 1 && selectedDetailColumnKeys.includes(column.key)"
                    @change="toggleDetailColumn(column.key)"
                  />
                  <span>{{ column.label }}</span>
                </label>
              </div>
              <p>Token 拆分、缓存和计费细节可在“调用链 → 成本快照”中查看。</p>
            </div>
          </details>
        </div>
        <div v-if="detailError" class="state-panel error-state">
          <strong>用量明细加载失败</strong>
          <p>{{ detailError }}</p>
          <button class="btn" @click="loadDetails">重试</button>
        </div>
        <div v-else-if="detailLoading" class="state-panel">
          <span class="loading-mark"></span
          ><strong>正在读取真实用量明细</strong>
        </div>
        <template v-else
          ><div class="table-wrap usage-table-wrap">
            <table
              class="data-table usage-table"
              :style="{ minWidth: `${detailTableMinWidth}px` }"
            >
              <thead>
                <tr>
                  <th
                    v-for="(column, index) in visibleDetailColumns"
                    :key="column.key"
                    :class="[
                      `column-${column.key}`,
                      { 'column-sticky-start': index === 0 },
                    ]"
                    :style="{ width: `${detailColumnWidth(column.key)}px` }"
                  >
                    <button class="sort-button" @click="sortBy(column.key)">
                      {{ column.label }} {{ sortIcon(column.key) }}
                    </button>
                  </th>
                  <th class="column-action">详情</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in detailRows"
                  :key="row.requestId"
                  class="usage-detail-row"
                  tabindex="0"
                  @click="openTrace(row)"
                  @keyup.enter="openTrace(row)"
                >
                  <td
                    v-for="(column, index) in visibleDetailColumns"
                    :key="column.key"
                    :class="[
                      `column-${column.key}`,
                      { 'column-sticky-start': index === 0 },
                    ]"
                    :style="{ width: `${detailColumnWidth(column.key)}px` }"
                  >
                    <span
                      v-if="column.key === 'status'"
                      :class="[
                        'status',
                        row.status === 'SUCCESS' ? 'ok' : 'danger',
                      ]"
                      >{{ statusLabel(row.status) }}</span
                    ><span
                      v-else-if="['costAmount', 'cacheNetSavings'].includes(column.key)"
                      >{{ money(row[column.key]) }}</span
                    ><span v-else-if="column.key === 'cacheHitRate'"
                      >{{ (Number(row.cacheHitRate || 0) * 100).toFixed(2) }}%</span
                    ><span v-else-if="column.key === 'latencyMs'"
                      >{{ number(row.latencyMs) }} ms</span
                    ><span
                      v-else-if="
                        [
                          'inputUncachedTokens',
                          'cacheReadTokens',
                          'cacheWriteTokens',
                          'outputTokens',
                          'reasoningTokens',
                          'totalTokens',
                        ].includes(column.key)
                      "
                      >{{ number(row[column.key]) }}</span
                    ><span v-else>{{ display(row[column.key]) }}</span>
                  </td>
                  <td class="column-action">
                    <button class="btn small" @click.stop="openTrace(row)">
                      调用链
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="!detailRows.length" class="state-panel empty-state">
            <strong>当前没有用量明细</strong>
            <p>调整时间范围或筛选条件后重试。</p>
          </div>
          <footer class="pagination">
            <span>共 {{ detailTotal }} 条</span>
            <template v-if="detailTotal > detailSize">
            <button
              class="btn"
              :disabled="detailPage === 1"
              @click="
                detailPage--;
                loadDetails();
              "
            >
              上一页</button
            ><span>第 {{ detailPage }} / {{ detailPageCount }} 页</span
            ><button
              class="btn"
              :disabled="detailPage >= detailPageCount"
              @click="
                detailPage++;
                loadDetails();
              "
            >
              下一页
            </button>
            </template>
          </footer></template
        >
      </section>
    </section>

    <aside v-if="selected" class="trace-drawer" aria-label="调用详情">
      <div class="detail-heading">
        <div>
          <span class="eyebrow">请求追踪</span
          ><strong>{{ selected.requestId }}</strong>
        </div>
        <button class="icon-button" @click="selected = undefined">×</button>
      </div>
      <div v-if="traceError" class="inline-alert danger">{{ traceError }}</div>
      <div v-else-if="traceLoading" class="state-panel">
        <span class="loading-mark"></span>正在读取 Attempt 与成本快照
      </div>
      <template v-else-if="traceDetail"
        ><section class="trace-summary">
          <div>
            <span>业务结果</span
            ><strong>{{
              statusLabel(traceDetail.status || selected.status)
            }}</strong>
          </div>
          <div>
            <span>服务模型</span
            ><strong>{{
              display(traceDetail.modelAlias || selected.modelAlias)
            }}</strong>
          </div>
          <div>
            <span>总成本</span
            ><strong
              >{{
                money(
                  traceDetail.costSnapshot?.costAmount ??
                    traceDetail.costAmount ??
                    selected.costAmount,
                )
              }}
              {{
                traceDetail.costSnapshot?.currency ||
                traceDetail.currency ||
                selected.currency ||
                ""
              }}</strong
            >
          </div>
        </section>
        <section class="detail-section">
          <h3>Attempt 链</h3>
          <div class="attempt-timeline">
            <article
              v-for="(attempt, index) in traceDetail.attempts || []"
              :key="attempt.id || index"
              class="attempt-card"
            >
              <span class="attempt-index">{{
                String(attempt.attemptNo || index + 1).padStart(2, "0")
              }}</span>
              <div>
                <strong>{{
                  attempt.providerInstanceName ||
                  attempt.providerInstanceId ||
                  "—"
                }}</strong>
                <p>{{ attempt.runtimeModelName || "—" }}</p>
              </div>
              <span
                :class="[
                  'status',
                  attempt.status === 'SUCCESS' ? 'ok' : 'danger',
                ]"
                >{{ statusLabel(attempt.status) }}</span
              >
              <div>
                <small>耗时</small
                ><strong>{{ number(attempt.latencyMs) }} ms</strong>
              </div>
              <div>
                <small>错误码</small
                ><strong>{{ display(attempt.errorCode) }}</strong>
              </div>
            </article>
          </div>
          <div
            v-if="!(traceDetail.attempts || []).length"
            class="state-panel empty-state compact-state"
          >
            该请求没有返回 Attempt 记录
          </div>
        </section>
        <section class="detail-section">
          <h3>成本快照</h3>
          <dl v-if="traceDetail.costSnapshot" class="cost-snapshot-list">
            <div
              v-for="item in costFields"
              :key="item.key"
              class="cost-snapshot-item"
            >
              <dt>{{ item.label }}</dt>
              <dd :title="display(traceDetail.costSnapshot[item.key])">
                {{ display(traceDetail.costSnapshot[item.key]) }}
              </dd>
            </div>
          </dl>
          <div v-else class="state-panel empty-state compact-state">
            该请求没有返回成本快照
          </div>
        </section></template
      >
    </aside>
  </section>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, reactive, ref } from "vue";
import {
  ApiOutlined,
  BarChartOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  DollarCircleOutlined,
  DownloadOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons-vue";
import { api, errorMessage, get, normalizePayload } from "../api/client";
import { formatDateTime } from "../format";

type Tab = "dashboard" | "details";
type Option = { label: string; value: string };
type Row = Record<string, any>;
const activeTab = ref<Tab>("dashboard");
const options = reactive<Record<string, Option[]>>({
  tenants: [],
  projects: [],
  apps: [],
  apiKeys: [],
  providers: [],
  models: [],
});
const filters = reactive({
  startAt: localDateTime(new Date(Date.now() - 29 * 86400000), true),
  endAt: localDateTime(new Date()),
  tenantId: "",
  projectId: "",
  appId: "",
  apiKeyId: "",
  providerId: "",
  modelAlias: "",
});
const endAtTracksNow = ref(true);
const dashboard = reactive<any>({
  summary: {},
  trend: [],
  providerCost: [],
  modelUsage: [],
  tenantCost: [],
  projectUsage: [],
  appTrend: [],
  keyRanking: [],
});
const dashboardLoading = ref(false),
  dashboardError = ref("");
const detailRows = ref<Row[]>([]),
  detailTotal = ref(0),
  detailPage = ref(1),
  detailSize = ref(20),
  detailLoading = ref(false),
  detailError = ref(""),
  detailSort = ref("createdAt"),
  detailOrder = ref<"asc" | "desc">("desc");
const selected = ref<Row>(),
  traceDetail = ref<any>(),
  traceLoading = ref(false),
  traceError = ref("");
const detailColumns = [
  { key: "createdAt", label: "时间" },
  { key: "tenantName", label: "租户" },
  { key: "projectName", label: "项目" },
  { key: "appName", label: "应用" },
  { key: "apiKeyName", label: "Virtual Key" },
  { key: "providerName", label: "供应商" },
  { key: "modelAlias", label: "服务模型" },
  { key: "runtimeModelName", label: "实际模型" },
  { key: "inputUncachedTokens", label: "未命中输入" },
  { key: "cacheReadTokens", label: "缓存命中" },
  { key: "cacheWriteTokens", label: "缓存写入" },
  { key: "outputTokens", label: "普通输出" },
  { key: "reasoningTokens", label: "推理 Token" },
  { key: "totalTokens", label: "总 Token" },
  { key: "cacheHitRate", label: "缓存命中率" },
  { key: "cacheNetSavings", label: "缓存净节省" },
  { key: "costStatus", label: "计费状态" },
  { key: "costAmount", label: "成本" },
  { key: "currency", label: "币种" },
  { key: "latencyMs", label: "耗时" },
  { key: "status", label: "状态" },
];
const detailColumnPreferenceKey = "tokensea_usage_detail_columns";
const defaultDetailColumnKeys = [
  "createdAt",
  "tenantName",
  "projectName",
  "appName",
  "apiKeyName",
  "providerName",
  "modelAlias",
  "totalTokens",
  "costAmount",
  "currency",
  "latencyMs",
  "status",
];
const selectedDetailColumnKeys = ref<string[]>(loadDetailColumnPreference());
const visibleDetailColumns = computed(() =>
  detailColumns.filter((column) => selectedDetailColumnKeys.value.includes(column.key)),
);
const detailColumnWidths: Record<string, number> = {
  createdAt: 158,
  tenantName: 110,
  projectName: 110,
  appName: 100,
  apiKeyName: 120,
  providerName: 90,
  modelAlias: 130,
  runtimeModelName: 130,
  inputUncachedTokens: 96,
  cacheReadTokens: 90,
  cacheWriteTokens: 90,
  outputTokens: 90,
  reasoningTokens: 96,
  totalTokens: 86,
  cacheHitRate: 96,
  cacheNetSavings: 100,
  costStatus: 90,
  costAmount: 90,
  currency: 58,
  latencyMs: 86,
  status: 68,
};
const detailTableMinWidth = computed(() =>
  Math.max(
    760,
    visibleDetailColumns.value.reduce(
      (total, column) => total + detailColumnWidth(column.key),
      72,
    ),
  ),
);
const costFields = [
  { key: "priceVersionId", label: "价格版本" },
  { key: "priceLayer", label: "价格层级" },
  { key: "usageSource", label: "Usage 来源" },
  { key: "costStatus", label: "计费状态" },
  { key: "inputUncachedTokens", label: "输入 Token（缓存未命中）" },
  { key: "cacheReadTokens", label: "输入 Token（缓存命中）" },
  { key: "cacheWriteTokens", label: "输入 Token（缓存写入）" },
  { key: "inputTokensTotal", label: "输入 Token 合计" },
  { key: "outputTokens", label: "普通输出 Token" },
  { key: "reasoningTokens", label: "推理 Token" },
  { key: "completionTokens", label: "输出 Token 合计" },
  { key: "billingBasis", label: "计费对象" },
  { key: "billingQuantity", label: "计费基数" },
  { key: "inputUnitPrice", label: "输入价格（缓存未命中）" },
  { key: "cacheReadUnitPrice", label: "输入价格（缓存命中）" },
  { key: "cacheReadMode", label: "缓存命中价格模式" },
  { key: "cacheWriteUnitPrice", label: "输入价格（缓存写入）" },
  { key: "cacheWriteMode", label: "缓存写入价格模式" },
  { key: "outputUnitPrice", label: "输出价格" },
  { key: "cacheGrossSavings", label: "缓存读取毛节省" },
  { key: "cacheWritePremium", label: "缓存写入溢价" },
  { key: "cacheStorageCost", label: "缓存存储成本" },
  { key: "cacheNetSavings", label: "缓存净节省" },
  { key: "actualCostAmount", label: "实际成本金额" },
  { key: "currency", label: "币种" },
  { key: "priceComponents", label: "生效价格组件" },
  { key: "costComponents", label: "本次成本分项" },
  { key: "usageEvidence", label: "上游 Usage 证据" },
  { key: "sourceRef", label: "来源依据" },
  { key: "calculatorVersion", label: "计算器版本" },
  { key: "createdAt", label: "快照时间" },
];
const summary = computed(() => dashboard.summary || {});
const activeLoading = computed(() =>
  activeTab.value === "dashboard"
    ? dashboardLoading.value
    : detailLoading.value,
);
const detailPageCount = computed(() =>
  Math.max(1, Math.ceil(detailTotal.value / detailSize.value)),
);
const hasTrend = computed(() =>
  dashboard.trend?.some(
    (item: any) =>
      Number(item.requests) > 0 ||
      Number(item.tokens) > 0 ||
      Number(item.cost) > 0,
  ),
);
const kpis = computed(() => [
  {
    key: "requests",
    label: "总请求数",
    value: number(summary.value.requests),
    note: `失败 ${number(summary.value.failedRequests)} 次`,
    tone: "blue",
    icon: markRaw(ApiOutlined),
  },
  {
    key: "tokens",
    label: "总 Token",
    value: number(summary.value.totalTokens),
    note: `输入 ${compact(summary.value.promptTokens)} / 输出 ${compact(summary.value.completionTokens)}`,
    tone: "green",
    icon: markRaw(DatabaseOutlined),
  },
  {
    key: "cost",
    label: "总成本（CNY）",
    value: money(summary.value.costAmount),
    note:
      Number(summary.value.fxMissingCount || 0) > 0
        ? `${number(summary.value.fxMissingCount)} 条缺少月度汇率，未计入汇总`
        : "原币成本已按月度汇率统一折算",
    tone: "violet",
    icon: markRaw(DollarCircleOutlined),
  },
  {
    key: "latency",
    label: "平均时延",
    value: `${number(Math.round(Number(summary.value.avgLatencyMs || 0)))} ms`,
    note: "真实请求端到端耗时",
    tone: "orange",
    icon: markRaw(ClockCircleOutlined),
  },
  {
    key: "tenants",
    label: "活跃租户",
    value: number(summary.value.activeTenants),
    note: "筛选周期内产生调用",
    tone: "cyan",
    icon: markRaw(TeamOutlined),
  },
  {
    key: "success",
    label: "成功率",
    value: `${Number(summary.value.successRate || 0).toFixed(2)}%`,
    note: "按业务请求结果计算",
    tone: "emerald",
    icon: markRaw(SafetyCertificateOutlined),
  },
]);
const palette = [
  "#2563eb",
  "#06b6d4",
  "#22c55e",
  "#8b5cf6",
  "#f59e0b",
  "#94a3b8",
];
const providerSegments = computed(() => {
  const rows = dashboard.providerCost || [],
    total = rows.reduce(
      (sum: number, item: any) => sum + Number(item.cost || 0),
      0,
    );
  return rows.map((item: any, index: number) => ({
    ...item,
    color: palette[index % palette.length],
    percent: total ? (Number(item.cost || 0) / total) * 100 : 0,
  }));
});
const donutBackground = computed(() => {
  let cursor = 0;
  const gap = 0.55;
  const stops: string[] = [];
  providerSegments.value.forEach((item: any) => {
    const start = cursor;
    const end = cursor + item.percent;
    stops.push(
      `#fff ${Math.max(0, start - gap)}% ${start}%`,
      `${item.color} ${start}% ${Math.max(start, end - gap)}%`,
    );
    cursor = end;
  });
  return stops.length ? `conic-gradient(${stops.join(",")})` : "#e8eef7";
});
const trendLabels = computed(() => {
  const rows = dashboard.trend || [];
  if (!rows.length) return [];
  const indices = [
    0,
    Math.floor((rows.length - 1) / 4),
    Math.floor((rows.length - 1) / 2),
    Math.floor(((rows.length - 1) * 3) / 4),
    rows.length - 1,
  ];
  return [...new Set(indices)].map((index) => ({
    date: rows[index].date,
    x: 48 + (656 * index) / Math.max(rows.length - 1, 1),
  }));
});
const appSeries = computed(() => {
  const rows = dashboard.appTrend || [];
  const dates = [...new Set(rows.map((item: any) => item.date))] as string[];
  const names = [...new Set(rows.map((item: any) => item.name))] as string[];
  const max = Math.max(
    1,
    ...rows.map((item: any) => Number(item.requests || 0)),
  );
  return names.map((name, index) => {
    const values = dates.map((date) =>
      Number(
        rows.find((item: any) => item.date === date && item.name === name)
          ?.requests || 0,
      ),
    );
    const dots = values.map((value, i) => ({
      x: 24 + (502 * i) / Math.max(values.length - 1, 1),
      y: 160 - (125 * value) / max,
      value,
    }));
    const points = dots.map((point) => `${point.x},${point.y}`).join(" ");
    const area = points ? `24,160 ${points} 526,160` : "";
    return {
      name,
      color: palette[index % palette.length],
      points,
      dots,
      area,
      total: values.reduce((sum, value) => sum + value, 0),
    };
  });
});
const appTrendLabels = computed(() => {
  const dates = [
    ...new Set((dashboard.appTrend || []).map((item: any) => item.date)),
  ] as string[];
  if (!dates.length) return [];
  const indexes = [0, Math.floor((dates.length - 1) / 2), dates.length - 1];
  return [...new Set(indexes)].map((index) => ({
    date: dates[index],
    x: 24 + (502 * index) / Math.max(dates.length - 1, 1),
  }));
});

function localDateTime(date: Date, start = false) {
  const value = new Date(date);
  if (start) value.setHours(0, 0, 0, 0);
  const pad = (v: number) => String(v).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}`;
}
function params(extra: Record<string, any> = {}) {
  const value: Record<string, any> = { ...extra };
  Object.entries(filters).forEach(([key, item]) => {
    if (!item) return;
    value[key] =
      key === "startAt" || key === "endAt"
        ? new Date(item).toISOString()
        : item;
  });
  return value;
}
async function loadOptions() {
  try {
    const response = await api.get("/api/usage-analysis/options");
    Object.assign(options, normalizePayload(response.data?.data || {}));
  } catch {
    Object.keys(options).forEach((key) => (options[key] = []));
  }
}
async function loadDashboard() {
  dashboardLoading.value = true;
  dashboardError.value = "";
  try {
    const response = await api.get("/api/usage-analysis/dashboard", {
      params: params(),
    });
    Object.assign(dashboard, {
      summary: {},
      trend: [],
      providerCost: [],
      modelUsage: [],
      tenantCost: [],
      projectUsage: [],
      appTrend: [],
      keyRanking: [],
      ...normalizePayload(response.data?.data || {}),
    });
  } catch (e) {
    dashboardError.value = errorMessage(e);
  } finally {
    dashboardLoading.value = false;
  }
}
async function loadDetails() {
  detailLoading.value = true;
  detailError.value = "";
  try {
    const response = await api.get("/api/usage-analysis/details", {
      params: params({
        page: detailPage.value,
        size: detailSize.value,
        sort: detailSort.value,
        order: detailOrder.value,
      }),
    });
    const data: any = normalizePayload(response.data?.data || {});
    detailRows.value = data.items || [];
    detailTotal.value = Number(data.total || 0);
  } catch (e) {
    detailError.value = errorMessage(e);
    detailRows.value = [];
    detailTotal.value = 0;
  } finally {
    detailLoading.value = false;
  }
}
function syncEndAtToNow() {
  if (endAtTracksNow.value) filters.endAt = localDateTime(new Date());
}
function switchTab(tab: Tab) {
  syncEndAtToNow();
  activeTab.value = tab;
  tab === "dashboard" ? loadDashboard() : loadDetails();
}
function applyFilters() {
  syncEndAtToNow();
  detailPage.value = 1;
  activeTab.value === "dashboard" ? loadDashboard() : loadDetails();
}
function resetFilters() {
  Object.assign(filters, {
    startAt: localDateTime(new Date(Date.now() - 29 * 86400000), true),
    endAt: localDateTime(new Date()),
    tenantId: "",
    projectId: "",
    appId: "",
    apiKeyId: "",
    providerId: "",
    modelAlias: "",
  });
  endAtTracksNow.value = true;
  detailPage.value = 1;
  applyFilters();
}
function refreshActive() {
  syncEndAtToNow();
  activeTab.value === "dashboard" ? loadDashboard() : loadDetails();
}
function sortBy(field: string) {
  detailSort.value === field
    ? (detailOrder.value = detailOrder.value === "asc" ? "desc" : "asc")
    : ((detailSort.value = field), (detailOrder.value = "asc"));
  loadDetails();
}
function sortIcon(field: string) {
  return detailSort.value === field
    ? detailOrder.value === "asc"
      ? "↑"
      : "↓"
    : "";
}
async function openTrace(row: Row) {
  selected.value = row;
  traceDetail.value = undefined;
  traceError.value = "";
  traceLoading.value = true;
  try {
    const value: any = await get(
      `/api/calls/${encodeURIComponent(row.requestId)}`,
    );
    traceDetail.value = {
      ...value,
      ...(value.usage || {}),
      costSnapshot: Array.isArray(value.costSnapshot)
        ? value.costSnapshot[0]
        : value.costSnapshot,
    };
  } catch (e) {
    traceError.value = errorMessage(e);
  } finally {
    traceLoading.value = false;
  }
}
function number(value: any) {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(
    Number(value || 0),
  );
}
function compact(value: any) {
  return new Intl.NumberFormat("zh-CN", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(Number(value || 0));
}
function money(value: any) {
  return new Intl.NumberFormat("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 6,
  }).format(Number(value || 0));
}
function display(value: any) {
  if (value === null || value === undefined || value === "") return "—";
  return formatDateTime(value) || String(value);
}
function statusLabel(value: any) {
  return value === "SUCCESS"
    ? "成功"
    : value === "FAILED"
      ? "失败"
      : display(value);
}
function shortDate(value: string) {
  return value?.slice(5) || value;
}
function maxValue(rows: any[], key: string) {
  return Math.max(0, ...(rows || []).map((item) => Number(item[key] || 0)));
}
function linePoints(rows: any[], key: string) {
  const max = Math.max(1, maxValue(rows, key));
  return (rows || [])
    .map(
      (item, index) =>
        `${48 + (656 * index) / Math.max(rows.length - 1, 1)},${215 - (180 * Number(item[key] || 0)) / max}`,
    )
    .join(" ");
}
function areaPoints(rows: any[], key: string) {
  const line = linePoints(rows, key);
  return line ? `48,215 ${line} 704,215` : "";
}
function barWidth(value: any, rows: any[], key: string) {
  const max = Math.max(
    1,
    ...(rows || []).map((item) => Number(item[key] || 0)),
  );
  return `${Math.max(3, (Number(value || 0) / max) * 100)}%`;
}
function share(value: any, rows: any[], key: string) {
  const total = (rows || []).reduce(
    (sum, item) => sum + Number(item[key] || 0),
    0,
  );
  const percent = total ? (Number(value || 0) / total) * 100 : 0;
  return `${percent.toFixed(1)}%`;
}
function verticalHeight(value: any, rows: any[], key: string) {
  const max = Math.max(
    1,
    ...(rows || []).map((item) => Number(item[key] || 0)),
  );
  return `${Math.max(5, (Number(value || 0) / max) * 100)}%`;
}
function detailColumnWidth(key: string) {
  return detailColumnWidths[key] || 96;
}
function loadDetailColumnPreference() {
  try {
    const stored = JSON.parse(localStorage.getItem(detailColumnPreferenceKey) || "[]");
    const validKeys = Array.isArray(stored)
      ? stored.filter((key) => detailColumns.some((column) => column.key === key))
      : [];
    return validKeys.length ? validKeys : [...defaultDetailColumnKeys];
  } catch {
    return [...defaultDetailColumnKeys];
  }
}
function saveDetailColumnPreference() {
  localStorage.setItem(detailColumnPreferenceKey, JSON.stringify(selectedDetailColumnKeys.value));
}
function toggleDetailColumn(key: string) {
  const selected = selectedDetailColumnKeys.value;
  if (selected.includes(key)) {
    if (selected.length === 1) return;
    selectedDetailColumnKeys.value = selected.filter((item) => item !== key);
  } else {
    selectedDetailColumnKeys.value = [...selected, key];
  }
  saveDetailColumnPreference();
}
function showAllDetailColumns() {
  selectedDetailColumnKeys.value = detailColumns.map((column) => column.key);
  saveDetailColumnPreference();
}
function resetDetailColumns() {
  selectedDetailColumnKeys.value = [...defaultDetailColumnKeys];
  saveDetailColumnPreference();
}
function exportCurrentPage() {
  if (!detailRows.value.length) return;
  const headers = visibleDetailColumns.value.map((item) => item.label);
  const keys = visibleDetailColumns.value.map((item) => item.key);
  const quote = (value: any) => `"${String(value ?? "").replace(/"/g, '""')}"`;
  const csv = [
    "\ufeff" + headers.map(quote).join(","),
    ...detailRows.value.map((row) =>
      keys
        .map((key) => quote(key === "createdAt" ? display(row[key]) : row[key]))
        .join(","),
    ),
  ].join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `TokenSea_usage_${Date.now()}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}
onMounted(async () => {
  await loadOptions();
  await loadDashboard();
});
</script>

<style scoped>
.usage-analysis-page {
  --usage-blue: #2563eb;
  --usage-ink: #14213d;
  --usage-muted: #6b7a90;
  --usage-border: #e2e8f2;
  --usage-soft: #f5f8fc;
  padding-bottom: 0;
}
.usage-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 8px;
}
.usage-header .header-actions {
  display: flex;
  gap: 10px;
}
.usage-header .btn {
  gap: 7px;
}
.usage-tabs {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.usage-tab {
  display: inline-flex;
  height: 32px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid #d7e0ec;
  border-radius: 7px;
  background: #f7f9fc;
  color: #607087;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
  transition:
    border-color 0.2s,
    background 0.2s,
    color 0.2s,
    box-shadow 0.2s;
}
.usage-tab:hover {
  border-color: #9db9ef;
  color: #2563eb;
}
.usage-tab.active {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
  box-shadow: 0 3px 8px rgba(37, 99, 235, 0.18);
}
.usage-filter-card {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  overflow-x: auto;
  overflow-y: hidden;
}
.filter-grid {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: 140px 140px 90px 90px 90px 120px 100px 110px;
  gap: 8px;
}
.filter-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}
.filter-field.time-field {
  width: 140px;
}
.filter-field > span {
  color: #59677b;
  font-size: 11px;
  font-weight: 700;
}
.usage-input,
.usage-select {
  width: 100%;
  min-width: 0;
  height: 32px;
  border: 1px solid #dbe3ee;
  border-radius: 7px;
  background: #fff;
  color: #26354d;
  font-size: 12px;
  outline: none;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.usage-input {
  padding: 0 8px;
}
.usage-select {
  padding: 0 24px 0 8px;
}
.usage-input:focus,
.usage-select:focus {
  border-color: #86aaf7;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08);
}
.filter-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 7px;
  padding-bottom: 0;
}
.filter-actions .btn {
  height: 32px;
  padding: 0 14px;
}
.usage-kpi-grid {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}
.usage-kpi-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--usage-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 12px rgba(35, 64, 112, 0.05);
}
.kpi-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 10px;
  font-size: 17px;
}
.kpi-icon.blue {
  background: #eaf2ff;
  color: #2563eb;
}
.kpi-icon.green {
  background: #e8f8ef;
  color: #16a464;
}
.kpi-icon.violet {
  background: #f4eaff;
  color: #8b5cf6;
}
.kpi-icon.orange {
  background: #fff2e4;
  color: #f28a22;
}
.kpi-icon.cyan {
  background: #e9f8fb;
  color: #0891b2;
}
.kpi-icon.emerald {
  background: #e8f8ef;
  color: #059669;
}
.usage-kpi-card > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}
.kpi-label {
  color: #617087;
  font-size: 12px;
  font-weight: 700;
}
.usage-kpi-card strong {
  overflow: hidden;
  margin-top: 2px;
  color: var(--usage-ink);
  font-size: 18px;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usage-kpi-card em {
  overflow: hidden;
  margin-top: 3px;
  color: #7b879a;
  font-size: 9px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analytics-grid {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 8px;
  overflow: hidden;
}
.analytics-card {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 11px 13px;
  border: 1px solid #dfe7f2;
  border-radius: 14px;
  background: linear-gradient(180deg, #fff 0%, #fbfdff 100%);
  box-shadow: 0 5px 18px rgba(35, 64, 112, 0.055);
  transition:
    border-color 0.2s,
    box-shadow 0.2s,
    transform 0.2s;
}
.analytics-card::before {
  position: absolute;
  top: 0;
  right: 16px;
  left: 16px;
  height: 2px;
  border-radius: 0 0 4px 4px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(37, 99, 235, 0.25),
    transparent
  );
  content: "";
}
.analytics-card:hover {
  border-color: #cbd9ec;
  box-shadow: 0 8px 24px rgba(35, 64, 112, 0.08);
  transform: translateY(-1px);
}
.analytics-card.span-2 {
  grid-column: span 2;
}
.analytics-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 7px;
  border-bottom: 1px solid #eef2f7;
}
.analytics-kicker {
  display: block;
  margin-bottom: 2px;
  color: #8a98ab;
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.11em;
}
.analytics-heading h2 {
  margin: 0;
  color: #1d2d48;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: -0.01em;
}
.analytics-heading p {
  margin: 2px 0 0;
  color: #98a4b5;
  font-size: 8px;
  line-height: 1.2;
}
.chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #66758b;
  font-size: 10px;
}
.chart-legend span::before {
  display: inline-block;
  width: 8px;
  height: 3px;
  margin-right: 5px;
  border-radius: 3px;
  content: "";
  vertical-align: middle;
}
.chart-legend .blue::before {
  background: #2563eb;
}
.chart-legend .green::before {
  background: #10b981;
}
.chart-legend .violet::before {
  background: #8b5cf6;
}
.unit-note {
  display: inline-flex;
  height: 20px;
  align-items: center;
  padding: 0 7px;
  border: 1px solid #e6ebf3;
  border-radius: 999px;
  background: #f8fafc;
  color: #7d899c;
  font-size: 8px;
  font-weight: 700;
}
.trend-heading {
  align-items: center;
}
.trend-summary {
  display: flex;
  align-items: center;
  gap: 5px;
}
.trend-summary > span {
  display: grid;
  grid-template-columns: 6px auto;
  gap: 1px 4px;
  align-items: center;
  padding: 5px 7px;
  border: 1px solid #e6edf7;
  border-radius: 8px;
  background: #f8fbff;
  color: #7d899b;
  font-size: 7px;
  line-height: 1.1;
}
.trend-summary i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  grid-row: span 2;
}
.trend-summary .blue-dot {
  background: #2563eb;
}
.trend-summary .green-dot {
  background: #10b981;
}
.trend-summary .violet-dot {
  background: #8b5cf6;
}
.trend-summary strong {
  color: #263a58;
  font-size: 9px;
}
.axis-scale text {
  fill: #a0abba;
  font-size: 8px;
}
.line-chart-shell {
  position: relative;
  height: calc(100% - 44px);
  min-height: 0;
  overflow: hidden;
  border-radius: 10px;
  background: linear-gradient(
    180deg,
    rgba(37, 99, 235, 0.035),
    rgba(255, 255, 255, 0)
  );
}
.line-chart {
  width: 100%;
  height: 100%;
  overflow: visible;
}
.grid-lines line {
  stroke: #e7edf6;
  stroke-width: 1;
  stroke-dasharray: 4 6;
}
.series {
  fill: none;
  stroke-width: 2.35;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 2px 2px rgba(37, 99, 235, 0.08));
}
.series.requests {
  stroke: #2563eb;
}
.series.tokens {
  stroke: #10b981;
}
.series.cost {
  stroke: #8b5cf6;
}
.axis-label {
  fill: #8895a8;
  font-size: 10px;
  text-anchor: middle;
}
.trend-highlight {
  position: absolute;
  top: 8px;
  right: 10px;
  display: flex;
  flex-direction: column;
  padding: 8px 11px;
  border: 1px solid #dce7f8;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 6px 16px rgba(20, 43, 86, 0.07);
  backdrop-filter: blur(6px);
}
.trend-highlight span,
.trend-highlight em {
  color: #8490a2;
  font-size: 9px;
  font-style: normal;
}
.trend-highlight strong {
  color: #244f9d;
  font-size: 16px;
}
.donut-layout {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 13px;
  align-items: center;
  height: calc(100% - 44px);
  min-height: 0;
}
.donut-shell {
  display: grid;
  place-items: center;
}
.donut {
  position: relative;
  display: grid;
  width: 126px;
  height: 126px;
  place-items: center;
  border: 5px solid #f4f7fb;
  border-radius: 50%;
  box-shadow:
    0 10px 24px rgba(37, 99, 235, 0.13),
    inset 0 0 0 1px rgba(255, 255, 255, 0.75);
  transform: rotate(-25deg);
}
.donut::before {
  position: absolute;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: linear-gradient(180deg, #fff, #f9fbfe);
  box-shadow:
    inset 0 0 0 1px #e7edf5,
    0 3px 10px rgba(42, 65, 100, 0.08);
  content: "";
}
.donut > div {
  z-index: 1;
  display: flex;
  align-items: center;
  flex-direction: column;
  transform: rotate(25deg);
}
.donut span,
.donut em,
.donut small {
  color: #8490a2;
  font-size: 8px;
  font-style: normal;
}
.donut strong {
  margin: 1px 0;
  color: #17253e;
  font-size: 14px;
  letter-spacing: -0.03em;
}
.donut small {
  margin-top: 2px;
}
.donut-legend {
  display: flex;
  min-width: 0;
  height: 100%;
  flex-direction: column;
  justify-content: flex-start;
  gap: 4px;
  overflow: hidden;
}
.provider-legend-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: 3px;
  padding: 4px 6px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fbfdff;
  transition:
    background 0.18s,
    border-color 0.18s,
    transform 0.18s;
}
.provider-legend-row:hover {
  border-color: #d7e3f3;
  background: #f5f9ff;
  transform: translateX(2px);
}
.provider-legend-main {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) 38px;
  gap: 6px;
  align-items: center;
}
.provider-legend-main > i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.12);
}
.provider-legend-main > span {
  overflow: hidden;
  color: #42516a;
  font-size: 9px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.provider-legend-main > strong {
  color: #1f314d;
  font-size: 9px;
  text-align: right;
}
.provider-legend-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding-left: 14px;
}
.provider-legend-meta em {
  color: #52617a;
  font-size: 8px;
  font-style: normal;
}
.provider-legend-meta small {
  overflow: hidden;
  color: #99a4b4;
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.provider-progress {
  height: 3px;
  margin-left: 14px;
  overflow: hidden;
  border-radius: 99px;
  background: #edf2f8;
}
.provider-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  box-shadow: 0 0 8px currentColor;
}
.horizontal-bars {
  display: flex;
  height: calc(100% - 44px);
  min-height: 0;
  overflow: hidden;
  flex-direction: column;
  justify-content: flex-start;
  gap: 5px;
  padding-top: 1px;
}
.horizontal-row {
  display: grid;
  grid-template-columns: 24px 92px minmax(74px, 1fr) 48px;
  gap: 7px;
  align-items: center;
  min-height: 32px;
  padding: 4px 5px;
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 9px;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.horizontal-row:hover {
  border-color: #e4ebf5;
  background: #f6f9fe;
}
.rank {
  display: grid;
  width: 21px;
  height: 21px;
  place-items: center;
  border-radius: 7px;
  background: #f0f3f8;
  color: #8b97a9;
  font-size: 8px;
  font-variant-numeric: tabular-nums;
}
.rank.top {
  background: linear-gradient(135deg, #e8f1ff, #dce9ff);
  color: #2563eb;
  font-weight: 800;
}
.model-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.bar-name {
  overflow: hidden;
  color: #33445f;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-info small {
  color: #9aa5b4;
  font-size: 7px;
}
.model-bar-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.model-bar-block small {
  color: #99a4b4;
  font-size: 7px;
  text-align: right;
}
.bar-track {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #edf2f8;
  box-shadow: inset 0 1px 2px rgba(30, 52, 84, 0.04);
}
.bar-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #60a5fa, #2563eb);
  box-shadow: 0 0 8px rgba(37, 99, 235, 0.18);
}
.horizontal-row > strong {
  color: #263a58;
  font-size: 9px;
  text-align: right;
}
.tenant-ranking {
  display: flex;
  height: calc(100% - 44px);
  min-height: 0;
  overflow: hidden;
  flex-direction: column;
  justify-content: flex-start;
  gap: 5px;
  padding-top: 1px;
}
.tenant-row {
  display: grid;
  grid-template-columns: 22px 92px minmax(60px, 1fr) 56px;
  gap: 7px;
  align-items: center;
  min-height: 32px;
  padding: 4px 5px;
  border: 1px solid transparent;
  border-radius: 8px;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.tenant-row:hover {
  border-color: #e4ebf5;
  background: #f7faff;
}
.tenant-index {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 6px;
  background: #eef2f7;
  color: #8793a5;
  font-size: 8px;
}
.tenant-index.top {
  background: #e8f1ff;
  color: #2563eb;
  font-weight: 800;
}
.tenant-name {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.tenant-name strong {
  overflow: hidden;
  color: #354761;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-name small {
  overflow: hidden;
  color: #9aa5b4;
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-meter {
  height: 7px;
  overflow: hidden;
  border-radius: 99px;
  background: #edf2f8;
}
.tenant-meter i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #93c5fd, #2563eb);
  box-shadow: 0 0 8px rgba(37, 99, 235, 0.16);
}
.tenant-cost {
  color: #253a58;
  font-size: 9px;
  text-align: right;
}
.project-bars {
  display: flex;
  height: calc(100% - 44px);
  min-height: 0;
  overflow: hidden;
  flex-direction: column;
  justify-content: flex-start;
  gap: 5px;
  padding-top: 1px;
}
.project-row {
  display: grid;
  grid-template-columns: 94px minmax(100px, 1fr);
  gap: 9px;
  align-items: center;
  min-height: 46px;
  padding: 5px 6px;
  border: 1px solid transparent;
  border-radius: 8px;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.project-row:hover {
  border-color: #e4ebf5;
  background: #f7faff;
}
.project-name {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.project-name span {
  overflow: hidden;
  color: #354761;
  font-size: 9px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.project-name small {
  color: #98a4b5;
  font-size: 7px;
}
.project-metrics {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}
.project-metric {
  display: grid;
  grid-template-columns: 33px minmax(50px, 1fr) 42px;
  gap: 5px;
  align-items: center;
}
.project-metric > span {
  color: #8a96a8;
  font-size: 7px;
}
.project-metric > strong {
  color: #344862;
  font-size: 8px;
  text-align: right;
}
.metric-track {
  height: 5px;
  overflow: hidden;
  border-radius: 99px;
  background: #edf2f8;
}
.metric-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}
.token-fill {
  background: linear-gradient(90deg, #6ee7b7, #10b981);
}
.request-fill {
  background: linear-gradient(90deg, #93c5fd, #2563eb);
}
.app-chart-shell {
  height: calc(100% - 44px);
  min-height: 0;
}
.app-legend {
  display: flex;
  gap: 6px;
  overflow: hidden;
  margin-bottom: 4px;
  color: #66758b;
  font-size: 8px;
}
.app-legend span {
  display: grid;
  min-width: 0;
  grid-template-columns: 7px minmax(0, 1fr);
  column-gap: 4px;
  align-items: center;
  padding: 3px 6px;
  border: 1px solid #e7edf5;
  border-radius: 7px;
  background: #fbfdff;
}
.app-legend i {
  width: 7px;
  height: 7px;
  grid-row: span 2;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.1);
}
.app-legend b {
  overflow: hidden;
  color: #4b5a70;
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.app-legend em {
  color: #9aa5b4;
  font-size: 6px;
  font-style: normal;
}
.app-chart {
  width: 100%;
  height: calc(100% - 31px);
}
.app-series {
  fill: none;
  stroke-width: 2.25;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 2px 2px rgba(37, 99, 235, 0.08));
}
.app-dot {
  stroke: #fff;
  stroke-width: 1.4;
  vector-effect: non-scaling-stroke;
}
.ranking-table {
  display: flex;
  height: calc(100% - 44px);
  min-height: 0;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid #e6edf6;
  border-radius: 9px;
  background: #fff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
}
.ranking-head,
.ranking-row {
  display: grid;
  grid-template-columns: 28px minmax(78px, 1fr) 54px 62px 68px;
  gap: 6px;
  align-items: center;
}
.ranking-head {
  padding: 6px 8px;
  border-bottom: 1px solid #e6edf6;
  background: linear-gradient(180deg, #f8fbff, #f3f7fc);
  color: #7d899b;
  font-size: 8px;
  font-weight: 700;
}
.ranking-row {
  min-height: 28px;
  padding: 0 8px;
  border-bottom: 1px solid #eef2f7;
  color: #536178;
  font-size: 9px;
  transition: background 0.16s;
}
.ranking-row:hover {
  background: #f7faff;
}
.ranking-row:nth-child(odd) {
  background: #fcfdff;
}
.ranking-row:hover {
  background: #f2f7ff;
}
.ranking-row strong {
  overflow: hidden;
  color: #2c405f;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ranking-index {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 6px;
  background: #eef2f7;
  color: #758299;
  font-size: 8px;
}
.ranking-index.top {
  background: linear-gradient(135deg, #e9f2ff, #dce9ff);
  color: #2563eb;
  font-weight: 800;
}
.text-action {
  border: 0;
  background: transparent;
  color: #2563eb;
  font-size: 11px;
  cursor: pointer;
}
.chart-empty {
  display: grid;
  height: calc(100% - 34px);
  min-height: 0;
  place-items: center;
  color: #8a96a8;
  font-size: 11px;
}
.dashboard-loading {
  min-height: 420px;
}
.usage-detail-card {
  padding: 0;
  overflow: hidden;
}
.detail-list-toolbar {
  position: relative;
  z-index: 5;
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--usage-border);
  background: #fff;
}
.detail-list-toolbar > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.detail-list-toolbar strong {
  color: #263a58;
  font-size: 13px;
}
.detail-list-toolbar span {
  color: #7b889b;
  font-size: 11px;
}
.column-selector {
  position: relative;
}
.column-selector > summary {
  list-style: none;
  cursor: pointer;
}
.column-selector > summary::-webkit-details-marker {
  display: none;
}
.column-selector[open] > summary {
  border-color: #2563eb;
  color: #2563eb;
}
.column-selector-panel {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  width: 430px;
  padding: 14px;
  border: 1px solid #d8e1ee;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(35, 55, 88, 0.18);
}
.column-selector-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 11px;
}
.column-selector-head > div:first-child {
  display: grid;
  gap: 2px;
}
.column-selector-head > div:last-child {
  display: flex;
  gap: 8px;
}
.column-selector-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px 8px;
}
.column-selector-grid label {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border-radius: 6px;
  color: #44536a;
  cursor: pointer;
}
.column-selector-grid label:hover {
  background: #f3f7fd;
}
.column-selector-grid input {
  flex: 0 0 auto;
}
.column-selector-grid span {
  overflow: hidden;
  color: inherit;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-selector-panel > p {
  margin: 11px 0 0;
  padding-top: 9px;
  border-top: 1px solid #edf1f6;
  color: #7a8798;
  font-size: 10px;
  line-height: 1.5;
}
.usage-table-wrap {
  max-height: calc(100vh - 420px);
  min-height: 360px;
  overflow-x: auto;
  overflow-y: auto;
  overscroll-behavior: contain;
  border-radius: 0;
  border-right: 0;
  border-left: 0;
}
.usage-table {
  width: 100%;
  table-layout: fixed;
}
.usage-table th {
  position: sticky;
  top: 0;
  z-index: 2;
}
.usage-table th,
.usage-table td {
  padding: 7px 7px;
  overflow: hidden;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: normal;
  line-height: 1.35;
  text-overflow: ellipsis;
}
.usage-table .sort-button {
  white-space: normal;
  line-height: 1.25;
  text-align: left;
}
.usage-table .column-createdAt {
  width: 116px;
  white-space: nowrap;
}
.usage-table .column-tenantName,
.usage-table .column-projectName,
.usage-table .column-appName {
  width: 68px;
}
.usage-table .column-apiKeyName {
  width: 82px;
}
.usage-table .column-providerName {
  width: 72px;
}
.usage-table .column-modelAlias {
  width: 82px;
}
.usage-table .column-runtimeModelName {
  width: 88px;
}
.usage-table .column-promptTokens,
.usage-table .column-completionTokens,
.usage-table .column-totalTokens {
  width: 58px;
  white-space: nowrap;
}
.usage-table .column-costAmount {
  width: 68px;
  white-space: nowrap;
}
.usage-table .column-currency {
  width: 42px;
  white-space: nowrap;
}
.usage-table .column-latencyMs {
  width: 56px;
  white-space: nowrap;
}
.usage-table .column-status {
  width: 50px;
  white-space: nowrap;
}
.usage-table .column-action {
  position: sticky;
  right: 0;
  z-index: 3;
  width: 72px;
  min-width: 72px;
  background: #fff;
  white-space: nowrap;
  box-shadow: -1px 0 0 #e4e9f1;
}
.usage-table thead .column-action {
  z-index: 5;
  background: #f4f7fb;
}
.usage-table .column-sticky-start {
  position: sticky;
  left: 0;
  z-index: 2;
  background: #fff;
  box-shadow: 1px 0 0 #e4e9f1;
}
.usage-table thead .column-sticky-start {
  z-index: 5;
  background: #f4f7fb;
}
.usage-detail-row:hover .column-sticky-start,
.usage-detail-row:hover .column-action {
  background: #f5f8ff;
}
.usage-detail-row:focus-visible .column-sticky-start,
.usage-detail-row:focus-visible .column-action {
  background: #eef4ff;
}
.usage-table td > span:not(.status) {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.usage-detail-row {
  cursor: pointer;
  transition: background-color 0.16s ease;
}
.usage-detail-row:hover {
  background: #f5f8ff;
}
.usage-detail-row:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: -2px;
  background: #eef4ff;
}
.pagination {
  padding: 13px 16px;
}
.trace-drawer {
  z-index: 1100;
}
@media (min-width: 981px) {
  .usage-analysis-page {
    display: flex;
    height: calc(100vh - 136px);
    height: calc(100dvh - 136px);
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  }
  .usage-analysis-page > .usage-header,
  .usage-analysis-page > .usage-filter-card {
    flex: 0 0 auto;
  }
  .usage-view {
    min-height: 0;
    flex: 1 1 auto;
  }
  .usage-dashboard-view {
    display: flex;
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
    padding: 0;
  }
  .usage-detail-view {
    display: flex;
    overflow: hidden;
  }
  .usage-detail-card {
    display: flex;
    min-height: 0;
    flex: 1 1 auto;
    flex-direction: column;
  }
  .usage-detail-card > .toolbar,
  .usage-detail-card > .detail-list-toolbar,
  .usage-detail-card > .pagination {
    flex: 0 0 auto;
  }
  .usage-detail-card > .usage-table-wrap,
  .usage-detail-card > .state-panel {
    min-height: 0;
    max-height: none;
    flex: 1 1 auto;
  }
}
@media (max-width: 1500px) {
  .usage-kpi-card {
    padding-right: 9px;
    padding-left: 9px;
  }
  .kpi-icon {
    width: 32px;
    height: 32px;
    flex-basis: 32px;
  }
  .usage-kpi-card strong {
    font-size: 16px;
  }
}
@media (max-width: 1100px) {
  .analytics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .usage-kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .usage-header {
    align-items: stretch;
    flex-direction: column;
  }
  .usage-kpi-grid,
  .analytics-grid {
    grid-template-columns: 1fr;
  }
  .analytics-card.span-2 {
    grid-column: span 1 !important;
  }
  .usage-filter-card {
    padding: 10px 12px;
  }
  .ranking-head,
  .ranking-row {
    grid-template-columns: 38px minmax(100px, 1fr) 75px 90px;
  }
  .ranking-head span:nth-child(3),
  .ranking-row span:nth-child(3) {
    display: none;
  }
  .donut-layout {
    grid-template-columns: 120px 1fr;
  }
  .donut {
    width: 120px;
    height: 120px;
  }
  .detail-list-toolbar {
    align-items: flex-start;
  }
  .detail-list-toolbar > div:first-child {
    display: grid;
    gap: 2px;
  }
  .column-selector-panel {
    right: -8px;
    width: min(430px, calc(100vw - 36px));
  }
  .column-selector-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
