/****** Object:  Table [sms].[sms_area]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_area](
	[area_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[parent_area_id] [bigint] NULL,
	[name] [nvarchar](200) NOT NULL,
	[full_path] [nvarchar](1000) NOT NULL,
	[level] [tinyint] NOT NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](0) NOT NULL,
	[created_by] [nvarchar](128) NOT NULL,
	[updated_at] [datetime2](0) NULL,
	[updated_by] [nvarchar](128) NULL,
	[rv] [timestamp] NOT NULL,
 CONSTRAINT [PK_sms_area] PRIMARY KEY CLUSTERED 
(
	[area_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_area_project_path] UNIQUE NONCLUSTERED 
(
	[project_id] ASC,
	[full_path] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_bore_size]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_bore_size](
	[bore_size_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](20) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_bore_size] PRIMARY KEY CLUSTERED 
(
	[bore_size_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_bore_size_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_incomplete_status]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_incomplete_status](
	[incomplete_status_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](40) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_incomplete_status] PRIMARY KEY CLUSTERED 
(
	[incomplete_status_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_incomplete_status_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_iso_type]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_iso_type](
	[iso_type_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](40) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_iso_type] PRIMARY KEY CLUSTERED 
(
	[iso_type_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_iso_type_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_packing_list]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_packing_list](
	[packing_list_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[packing_list_name] [nvarchar](255) NOT NULL,
	[vehicle_id] [bigint] NULL,
	[position_id] [int] NULL,
	[packing_date] [datetime2](7) NOT NULL,
	[total_spools_count] [int] NULL,
	[total_weight_kg] [decimal](10, 3) NULL,
	[notes] [nvarchar](max) NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](7) NOT NULL,
	[created_by] [nvarchar](128) NULL,
	[updated_at] [datetime2](7) NULL,
	[rv] [timestamp] NOT NULL,
PRIMARY KEY CLUSTERED 
(
	[packing_list_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_packing_list_project_name] UNIQUE NONCLUSTERED 
(
	[project_id] ASC,
	[packing_list_name] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_packing_list_spool]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_packing_list_spool](
	[packing_list_spool_id] [bigint] IDENTITY(1,1) NOT NULL,
	[packing_list_id] [bigint] NOT NULL,
	[spool_id] [bigint] NOT NULL,
	[sequence_number] [int] NULL,
	[added_at] [datetime2](7) NOT NULL,
	[added_by] [nvarchar](128) NULL,
	[rv] [timestamp] NOT NULL,
PRIMARY KEY CLUSTERED 
(
	[packing_list_spool_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_packing_list_spool_unique] UNIQUE NONCLUSTERED 
(
	[packing_list_id] ASC,
	[spool_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_position]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_position](
	[position_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](40) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_position] PRIMARY KEY CLUSTERED 
(
	[position_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_position_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spec]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spec](
	[spec_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[code] [nvarchar](50) NOT NULL,
	[description] [nvarchar](200) NULL,
	[material_type] [nvarchar](50) NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](0) NOT NULL,
	[created_by] [nvarchar](128) NOT NULL,
	[updated_at] [datetime2](0) NULL,
	[updated_by] [nvarchar](128) NULL,
	[rv] [timestamp] NOT NULL,
 CONSTRAINT [PK_sms_spec] PRIMARY KEY CLUSTERED 
(
	[spec_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_spec_project_code] UNIQUE NONCLUSTERED 
(
	[project_id] ASC,
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spool]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spool](
	[spool_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[spool_code] [nvarchar](80) NOT NULL,
	[spool_suffix] [nvarchar](20) NULL,
	[line_code] [nvarchar](50) NULL,
	[unit_id] [int] NULL,
	[service] [nvarchar](20) NULL,
	[train] [nvarchar](20) NULL,
	[module] [nvarchar](80) NULL,
	[iso_type_id] [int] NULL,
	[spec_id] [bigint] NULL,
	[iso_revision_date] [date] NULL,
	[subcontractor_id] [bigint] NULL,
	[area_id] [bigint] NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](0) NOT NULL,
	[created_by] [nvarchar](128) NOT NULL,
	[updated_at] [datetime2](0) NULL,
	[updated_by] [nvarchar](128) NULL,
	[rv] [timestamp] NOT NULL,
	[packing_list_id] [bigint] NULL,
 CONSTRAINT [PK_sms_spool] PRIMARY KEY CLUSTERED 
(
	[spool_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_spool_project_code_suffix] UNIQUE NONCLUSTERED 
(
	[project_id] ASC,
	[spool_code] ASC,
	[spool_suffix] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spool_event]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spool_event](
	[event_id] [bigint] IDENTITY(1,1) NOT NULL,
	[event_date] [datetime2](0) NOT NULL,
	[spool_id] [bigint] NOT NULL,
	[event_type] [varchar](40) NOT NULL,
	[old_value] [nvarchar](200) NULL,
	[new_value] [nvarchar](200) NULL,
	[source] [varchar](40) NULL,
	[created_at] [datetime2](0) NOT NULL,
	[created_by] [nvarchar](128) NOT NULL,
 CONSTRAINT [PK_sms_spool_event] PRIMARY KEY CLUSTERED 
(
	[event_id] ASC,
	[event_date] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spool_property]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spool_property](
	[spool_id] [bigint] NOT NULL,
	[diameter_inches] [decimal](8, 3) NULL,
	[diameter] [decimal](8, 3) NULL,
	[bore_size_id] [int] NULL,
	[weight_kg] [decimal](10, 3) NULL,
	[updated_at] [datetime2](0) NOT NULL,
	[rv] [timestamp] NOT NULL,
 CONSTRAINT [PK_sms_spool_property] PRIMARY KEY CLUSTERED 
(
	[spool_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spool_status]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spool_status](
	[status_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](40) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_spool_status] PRIMARY KEY CLUSTERED 
(
	[status_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_spool_status_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_spool_status_flags]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_spool_status_flags](
	[spool_id] [bigint] NOT NULL,
	[status_id] [int] NULL,
	[incomplete_status_id] [int] NULL,
	[position_id] [int] NULL,
	[hold] [bit] NOT NULL,
	[damaged] [bit] NOT NULL,
	[returned_to_factory] [bit] NOT NULL,
	[position_status_discrepancy] [bit] NOT NULL,
	[review_discrepancy] [bit] NOT NULL,
	[last_event_date] [datetime2](0) NULL,
	[pca_status_date] [date] NULL,
	[pca_entry_date] [date] NULL,
	[updated_at] [datetime2](0) NOT NULL,
	[rv] [timestamp] NOT NULL,
 CONSTRAINT [PK_sms_spool_status_flags] PRIMARY KEY CLUSTERED 
(
	[spool_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_subcontractor]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_subcontractor](
	[subcontractor_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[code] [varchar](50) NOT NULL,
	[name] [nvarchar](200) NOT NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](0) NOT NULL,
	[created_by] [nvarchar](128) NOT NULL,
	[updated_at] [datetime2](0) NULL,
	[updated_by] [nvarchar](128) NULL,
	[rv] [timestamp] NOT NULL,
 CONSTRAINT [PK_sms_subcontractor] PRIMARY KEY CLUSTERED 
(
	[subcontractor_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_sub_project_code] UNIQUE NONCLUSTERED 
(
	[project_id] ASC,
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_unit]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_unit](
	[unit_id] [int] IDENTITY(1,1) NOT NULL,
	[code] [varchar](20) NOT NULL,
	[name] [nvarchar](100) NOT NULL,
	[sort_order] [int] NULL,
	[is_active] [bit] NOT NULL,
 CONSTRAINT [PK_sms_unit] PRIMARY KEY CLUSTERED 
(
	[unit_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY],
 CONSTRAINT [UQ_sms_unit_code] UNIQUE NONCLUSTERED 
(
	[code] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
/****** Object:  Table [sms].[sms_vehicle]    Script Date: 11/05/2026 20:24:17 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
CREATE TABLE [sms].[sms_vehicle](
	[vehicle_id] [bigint] IDENTITY(1,1) NOT NULL,
	[project_id] [int] NOT NULL,
	[company] [nvarchar](255) NULL,
	[license_plate] [nvarchar](50) NOT NULL,
	[vehicle_name] [nvarchar](255) NULL,
	[vehicle_type] [nvarchar](100) NULL,
	[capacity_weight_kg] [decimal](10, 2) NULL,
	[is_active] [bit] NOT NULL,
	[created_at] [datetime2](7) NOT NULL,
	[created_by] [nvarchar](128) NULL,
	[updated_at] [datetime2](7) NULL,
	[rv] [timestamp] NOT NULL,
PRIMARY KEY CLUSTERED 
(
	[vehicle_id] ASC
)WITH (STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
) ON [PRIMARY]
GO
ALTER TABLE [sms].[sms_area] ADD  CONSTRAINT [DF_sms_area_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_area] ADD  CONSTRAINT [DF_sms_area_created_at]  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_bore_size] ADD  CONSTRAINT [DF_sms_bore_size_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_incomplete_status] ADD  CONSTRAINT [DF_sms_incomplete_status_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_iso_type] ADD  CONSTRAINT [DF_sms_iso_type_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_packing_list] ADD  DEFAULT (sysutcdatetime()) FOR [packing_date]
GO
ALTER TABLE [sms].[sms_packing_list] ADD  DEFAULT ((0)) FOR [total_spools_count]
GO
ALTER TABLE [sms].[sms_packing_list] ADD  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_packing_list] ADD  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_packing_list_spool] ADD  DEFAULT (sysutcdatetime()) FOR [added_at]
GO
ALTER TABLE [sms].[sms_position] ADD  CONSTRAINT [DF_sms_position_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_spec] ADD  CONSTRAINT [DF_sms_spec_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_spec] ADD  CONSTRAINT [DF_sms_spec_created_at]  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_spool] ADD  CONSTRAINT [DF_sms_spool_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_spool] ADD  CONSTRAINT [DF_sms_spool_created_at]  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_spool_event] ADD  CONSTRAINT [DF_sms_sp_evt_created_at]  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_spool_property] ADD  CONSTRAINT [DF_sms_sp_prop_updated_at]  DEFAULT (sysutcdatetime()) FOR [updated_at]
GO
ALTER TABLE [sms].[sms_spool_status] ADD  CONSTRAINT [DF_sms_spool_status_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_hold]  DEFAULT ((0)) FOR [hold]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_damaged]  DEFAULT ((0)) FOR [damaged]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_returned]  DEFAULT ((0)) FOR [returned_to_factory]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_pos_disc]  DEFAULT ((0)) FOR [position_status_discrepancy]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_rev_disc]  DEFAULT ((0)) FOR [review_discrepancy]
GO
ALTER TABLE [sms].[sms_spool_status_flags] ADD  CONSTRAINT [DF_sms_ssf_updated_at]  DEFAULT (sysutcdatetime()) FOR [updated_at]
GO
ALTER TABLE [sms].[sms_subcontractor] ADD  CONSTRAINT [DF_sms_sub_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_subcontractor] ADD  CONSTRAINT [DF_sms_sub_created_at]  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_unit] ADD  CONSTRAINT [DF_sms_unit_is_active]  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_vehicle] ADD  DEFAULT ((1)) FOR [project_id]
GO
ALTER TABLE [sms].[sms_vehicle] ADD  DEFAULT ((1)) FOR [is_active]
GO
ALTER TABLE [sms].[sms_vehicle] ADD  DEFAULT (sysutcdatetime()) FOR [created_at]
GO
ALTER TABLE [sms].[sms_area]  WITH CHECK ADD  CONSTRAINT [FK_sms_area_parent] FOREIGN KEY([parent_area_id])
REFERENCES [sms].[sms_area] ([area_id])
GO
ALTER TABLE [sms].[sms_area] CHECK CONSTRAINT [FK_sms_area_parent]
GO
ALTER TABLE [sms].[sms_area]  WITH CHECK ADD  CONSTRAINT [FK_sms_area_project] FOREIGN KEY([project_id])
REFERENCES [atlas].[atlas_project] ([project_id])
GO
ALTER TABLE [sms].[sms_area] CHECK CONSTRAINT [FK_sms_area_project]
GO
ALTER TABLE [sms].[sms_packing_list]  WITH CHECK ADD  CONSTRAINT [FK_packing_list_position] FOREIGN KEY([position_id])
REFERENCES [sms].[sms_position] ([position_id])
GO
ALTER TABLE [sms].[sms_packing_list] CHECK CONSTRAINT [FK_packing_list_position]
GO
ALTER TABLE [sms].[sms_packing_list]  WITH CHECK ADD  CONSTRAINT [FK_packing_list_project] FOREIGN KEY([project_id])
REFERENCES [atlas].[atlas_project] ([project_id])
GO
ALTER TABLE [sms].[sms_packing_list] CHECK CONSTRAINT [FK_packing_list_project]
GO
ALTER TABLE [sms].[sms_packing_list_spool]  WITH CHECK ADD  CONSTRAINT [FK_packing_list_spool_packing_list] FOREIGN KEY([packing_list_id])
REFERENCES [sms].[sms_packing_list] ([packing_list_id])
ON DELETE CASCADE
GO
ALTER TABLE [sms].[sms_packing_list_spool] CHECK CONSTRAINT [FK_packing_list_spool_packing_list]
GO
ALTER TABLE [sms].[sms_packing_list_spool]  WITH CHECK ADD  CONSTRAINT [FK_packing_list_spool_spool] FOREIGN KEY([spool_id])
REFERENCES [sms].[sms_spool] ([spool_id])
GO
ALTER TABLE [sms].[sms_packing_list_spool] CHECK CONSTRAINT [FK_packing_list_spool_spool]
GO
ALTER TABLE [sms].[sms_spec]  WITH CHECK ADD  CONSTRAINT [FK_sms_spec_project] FOREIGN KEY([project_id])
REFERENCES [atlas].[atlas_project] ([project_id])
GO
ALTER TABLE [sms].[sms_spec] CHECK CONSTRAINT [FK_sms_spec_project]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_area] FOREIGN KEY([area_id])
REFERENCES [sms].[sms_area] ([area_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_area]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_iso_type] FOREIGN KEY([iso_type_id])
REFERENCES [sms].[sms_iso_type] ([iso_type_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_iso_type]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_packing_list] FOREIGN KEY([packing_list_id])
REFERENCES [sms].[sms_packing_list] ([packing_list_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_packing_list]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_project] FOREIGN KEY([project_id])
REFERENCES [atlas].[atlas_project] ([project_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_project]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_spec] FOREIGN KEY([spec_id])
REFERENCES [sms].[sms_spec] ([spec_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_spec]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_subcontractor] FOREIGN KEY([subcontractor_id])
REFERENCES [sms].[sms_subcontractor] ([subcontractor_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_subcontractor]
GO
ALTER TABLE [sms].[sms_spool]  WITH CHECK ADD  CONSTRAINT [FK_sms_spool_unit] FOREIGN KEY([unit_id])
REFERENCES [sms].[sms_unit] ([unit_id])
GO
ALTER TABLE [sms].[sms_spool] CHECK CONSTRAINT [FK_sms_spool_unit]
GO
ALTER TABLE [sms].[sms_spool_event]  WITH CHECK ADD  CONSTRAINT [FK_sms_sp_evt_spool] FOREIGN KEY([spool_id])
REFERENCES [sms].[sms_spool] ([spool_id])
GO
ALTER TABLE [sms].[sms_spool_event] CHECK CONSTRAINT [FK_sms_sp_evt_spool]
GO
ALTER TABLE [sms].[sms_spool_property]  WITH CHECK ADD  CONSTRAINT [FK_sms_sp_prop_bore_size] FOREIGN KEY([bore_size_id])
REFERENCES [sms].[sms_bore_size] ([bore_size_id])
GO
ALTER TABLE [sms].[sms_spool_property] CHECK CONSTRAINT [FK_sms_sp_prop_bore_size]
GO
ALTER TABLE [sms].[sms_spool_property]  WITH CHECK ADD  CONSTRAINT [FK_sms_sp_prop_spool] FOREIGN KEY([spool_id])
REFERENCES [sms].[sms_spool] ([spool_id])
GO
ALTER TABLE [sms].[sms_spool_property] CHECK CONSTRAINT [FK_sms_sp_prop_spool]
GO
ALTER TABLE [sms].[sms_spool_status_flags]  WITH CHECK ADD  CONSTRAINT [FK_sms_ssf_incomplete_status] FOREIGN KEY([incomplete_status_id])
REFERENCES [sms].[sms_incomplete_status] ([incomplete_status_id])
GO
ALTER TABLE [sms].[sms_spool_status_flags] CHECK CONSTRAINT [FK_sms_ssf_incomplete_status]
GO
ALTER TABLE [sms].[sms_spool_status_flags]  WITH CHECK ADD  CONSTRAINT [FK_sms_ssf_position] FOREIGN KEY([position_id])
REFERENCES [sms].[sms_position] ([position_id])
GO
ALTER TABLE [sms].[sms_spool_status_flags] CHECK CONSTRAINT [FK_sms_ssf_position]
GO
ALTER TABLE [sms].[sms_spool_status_flags]  WITH CHECK ADD  CONSTRAINT [FK_sms_ssf_spool] FOREIGN KEY([spool_id])
REFERENCES [sms].[sms_spool] ([spool_id])
GO
ALTER TABLE [sms].[sms_spool_status_flags] CHECK CONSTRAINT [FK_sms_ssf_spool]
GO
ALTER TABLE [sms].[sms_spool_status_flags]  WITH CHECK ADD  CONSTRAINT [FK_sms_ssf_status] FOREIGN KEY([status_id])
REFERENCES [sms].[sms_spool_status] ([status_id])
GO
ALTER TABLE [sms].[sms_spool_status_flags] CHECK CONSTRAINT [FK_sms_ssf_status]
GO
ALTER TABLE [sms].[sms_subcontractor]  WITH CHECK ADD  CONSTRAINT [FK_sms_sub_project] FOREIGN KEY([project_id])
REFERENCES [atlas].[atlas_project] ([project_id])
GO
ALTER TABLE [sms].[sms_subcontractor] CHECK CONSTRAINT [FK_sms_sub_project]
GO
